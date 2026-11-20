package com.buse.smartfreeze.data.training

import android.util.Log
import com.buse.smartfreeze.data.db.AppDatabase
import com.buse.smartfreeze.data.db.SampleEntity
import com.buse.smartfreeze.data.repository.ModelRepository
import com.buse.smartfreeze.domain.BaselineStats
import com.buse.smartfreeze.domain.TrainingPolicy
import com.buse.smartfreeze.domain.model.RawSample
import com.buse.smartfreeze.ml.IsolationForestTrainer
import com.buse.smartfreeze.ml.ModelBundle
import com.buse.smartfreeze.ml.PersonalModelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TrainingState(
    val running: Boolean = false,
    val demo: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

/**
 * Trains the PERSONAL Isolation Forest on this phone from its own recorded normal data.
 *
 * Triggered automatically (after each monitoring session and by a daily WorkManager job) when
 * [TrainingPolicy] says the baseline is complete or the model is due for retraining, or manually
 * ("Train now", "Retrain now", research-mode "Quick demo baseline").
 * Training runs on a background dispatcher; the UI and the sampling loop keep running.
 */
class PersonalModelTrainer(
    private val db: AppDatabase,
    private val models: ModelRepository,
    private val scope: CoroutineScope,
    val policy: TrainingPolicy = TrainingPolicy(),
    private val builder: PersonalModelBuilder = PersonalModelBuilder(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(TrainingState())
    val state: StateFlow<TrainingState> = _state.asStateFlow()
    private val mutex = Mutex()

    private fun from(now: Long) = now - LOOKBACK_DAYS * 86_400_000L

    /** Live progress of the baseline (counts only trainable, real samples). */
    fun baselineStats(): Flow<BaselineStats> = db.samples().baselineStatsFlow(from(clock()))

    fun trainNow(demo: Boolean) { scope.launch { train(demo) } }

    fun requestCheck() { scope.launch { trainIfDue() } }

    /** Trains if no full personal model exists and the baseline is complete, or if retraining is due. */
    suspend fun trainIfDue() {
        if (mutex.isLocked) return
        try {
            val now = clock()
            val personal = models.bundleOrNull?.takeIf { it.origin == ModelBundle.ORIGIN_ON_DEVICE }
            if (personal == null) {
                if (policy.isReady(db.samples().baselineStats(from(now)))) train(demo = false)
            } else {
                val trainedAt = (personal.metadata["trained_at_ms"] as? Number)?.toLong() ?: 0L
                if (policy.shouldRetrain(trainedAt, db.samples().countTrainableSince(trainedAt), now)) train(demo = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "training check failed", e)
        }
    }

    private suspend fun train(demo: Boolean) = mutex.withLock {
        _state.value = TrainingState(running = true, demo = demo, message = "Training your personal model…")
        try {
            val now = clock()
            val stats = db.samples().baselineStats(from(now))
            if (demo && !policy.isDemoReady(stats)) {
                throw IsolationForestTrainer.NotEnoughDataException("a quick demo baseline needs at least ${policy.demoMinMinutes.toInt()} minutes of monitoring")
            }
            if (!demo && !policy.isReady(stats)) {
                throw IsolationForestTrainer.NotEnoughDataException("still needed: " + policy.remaining(stats).joinToString(", "))
            }
            val entities = db.samples().trainingSamples(from(now), MAX_SAMPLES)
            val samples = entities.mapNotNull { e -> e.toRaw()?.let { PersonalModelBuilder.LabelledSample(e.sessionId, e.activityLabel, it) } }
            val result = withContext(Dispatchers.Default) {
                builder.build(samples, demo, now, mapOf(
                    "baseline_days" to stats.days,
                    "baseline_hours" to Math.round(stats.monitoredHours * 10) / 10.0,
                ))
            }
            models.installPersonal(result.bundle)
            val what = if (demo) "Demo-quality model" else "Personal model"
            _state.value = TrainingState(message = "$what trained on ${samples.size} samples in %.1f s.".format(result.totalMillis / 1000.0))
            Log.i(TAG, "trained ${result.bundle.modelVersion}: ${samples.size} samples, ${result.totalMillis} ms")
        } catch (e: CancellationException) {
            _state.value = TrainingState()
            throw e
        } catch (e: IsolationForestTrainer.NotEnoughDataException) {
            _state.value = TrainingState(message = "Not enough data yet: ${e.message}", failed = true)
        } catch (e: Exception) {
            Log.e(TAG, "training failed", e)
            _state.value = TrainingState(message = "Training failed: ${e.message ?: e.javaClass.simpleName}", failed = true)
        }
    }

    private fun SampleEntity.toRaw(): RawSample? {
        val battery = batteryLevelPct ?: return null
        return try {
            RawSample(timestampMs, intervalS, memTotalBytes, memAvailBytes, memThresholdBytes, lowMemory, battery,
                isCharging ?: false, thermalStatus, thermalHeadroom, batteryTempC, storageTotalBytes, storageAvailBytes)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val TAG = "PersonalModelTrainer"
        /** Train on (at most) the most recent 30 days and 20,000 samples. */
        const val LOOKBACK_DAYS = 30
        const val MAX_SAMPLES = 20_000
    }
}
