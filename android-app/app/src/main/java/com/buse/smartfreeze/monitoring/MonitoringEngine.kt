package com.buse.smartfreeze.monitoring

import android.util.Log
import com.buse.smartfreeze.data.collector.DeviceReading
import com.buse.smartfreeze.data.collector.MetricsSource
import com.buse.smartfreeze.data.repository.ModelRepository
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.data.repository.MonitoringRepository
import com.buse.smartfreeze.data.repository.SettingsRepository
import com.buse.smartfreeze.domain.AlertPolicy
import com.buse.smartfreeze.domain.AnomalyDetector
import com.buse.smartfreeze.domain.model.DetectionResult
import com.buse.smartfreeze.domain.model.RawSample
import com.buse.smartfreeze.domain.model.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MonitoringMode { FOREGROUND, BACKGROUND }

data class MonitoringState(
    val running: Boolean = false,
    val sessionId: Long? = null,
    val startedAtMs: Long? = null,
    val mode: MonitoringMode = MonitoringMode.FOREGROUND,
    val configuredIntervalS: Int = 10,
    val effectiveIntervalS: Int = 10,
    val batterySaving: Boolean = false,
    val pausedReason: String? = null,
    val samplesTaken: Int = 0,
    val duplicatesSkipped: Int = 0,
    val lastReading: DeviceReading? = null,
    val lastResult: DetectionResult? = null,
    val lastInferenceMicros: Long? = null,
    val activity: String? = null,
    /** Recent (timestamp, anomaly score) pairs for live charts; empty during cold start. */
    val recentScores: List<Pair<Long, Double>> = emptyList(),
    /** Recent (timestamp, available-memory ratio) pairs for live charts. */
    val recentMemRatio: List<Pair<Long, Double>> = emptyList(),
)

/**
 * The sampling engine. Owned by the application container so that UI screens and the optional
 * foreground service observe one shared state.
 *
 * Lifecycle rules (respecting Android background-execution limits):
 *  - FOREGROUND mode: sampling pauses automatically when the app leaves the screen and resumes when
 *    it returns (see [onAppForegroundChanged]). Gaps are recorded as larger sample_gap_s values.
 *  - BACKGROUND mode: only while MonitoringService is running as a user-started foreground service.
 *  - Battery-aware: when the battery is <= 20 % and not charging, the interval is doubled (max 60 s).
 */
class MonitoringEngine(
    private val scope: CoroutineScope,
    private val source: MetricsSource,
    private val repo: MonitoringRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val notifier: AlertNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    /** True while a research controlled workload runs: such samples are excluded from personal training. */
    private val isWorkloadRunning: () -> Boolean = { false },
    /** Called after a session ends (the app uses it to check whether the personal model can be trained). */
    private val onSessionEnded: () -> Unit = {},
) {
    private val _state = MutableStateFlow(MonitoringState())
    val state: StateFlow<MonitoringState> = _state.asStateFlow()

    private val lock = Mutex()
    private var job: Job? = null
    @Volatile private var detector: AnomalyDetector? = null

    init {
        // When a new model becomes active (e.g. the personal model has just been trained), switch to it
        // immediately, even in the middle of a session. The new detector starts a fresh feature window.
        scope.launch {
            models.status.collect { st ->
                if (_state.value.running && (st is ModelStatus.Ready || st is ModelStatus.NotAvailable)) {
                    detector = AnomalyDetector(models.bundleOrNull)
                }
            }
        }
    }
    private val policy = AlertPolicy()
    private var lastTimestamp = 0L
    private var userPaused = false
    private var appInForeground = true

    fun start(mode: MonitoringMode = MonitoringMode.FOREGROUND) {
        scope.launch {
            lock.withLock {
                if (_state.value.running) {
                    _state.update { it.copy(mode = mode) }
                    updatePause()
                    return@withLock
                }
                val s = settings.current()
                val now = clock()
                val sessionId = repo.startSession(s.intervalS, mode.name.lowercase(), s.researchMode, now)
                if (s.currentActivity != null) repo.setActivity(sessionId, s.currentActivity, now)
                detector = AnomalyDetector(models.bundleOrNull)
                policy.reset()
                policy.cooldownMs = s.cooldownMinutes * 60_000L
                policy.minSeverity = runCatching { Severity.valueOf(s.minAlertSeverity) }.getOrDefault(Severity.MEDIUM)
                lastTimestamp = 0L
                userPaused = false
                _state.value = MonitoringState(
                    running = true, sessionId = sessionId, startedAtMs = now, mode = mode,
                    configuredIntervalS = s.intervalS, effectiveIntervalS = s.intervalS, activity = s.currentActivity,
                )
                updatePause()
                job = scope.launch { loop() }
            }
        }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                val id = _state.value.sessionId ?: return@withLock
                job?.cancel()
                job = null
                repo.endSession(id, clock())
                detector = null
                _state.update { MonitoringState(lastReading = it.lastReading, lastResult = it.lastResult) }
            }
            onSessionEnded()
        }
    }

    fun setUserPaused(paused: Boolean) {
        userPaused = paused
        updatePause()
    }

    /** Called from ProcessLifecycleOwner: the app UI became visible (true) or hidden (false). */
    fun onAppForegroundChanged(visible: Boolean) {
        appInForeground = visible
        updatePause()
    }

    private fun updatePause() {
        val reason = when {
            userPaused -> "Paused by you"
            !appInForeground && _state.value.mode == MonitoringMode.FOREGROUND -> "Paused: app not on screen"
            else -> null
        }
        _state.update { it.copy(pausedReason = reason) }
    }

    fun setActivity(label: String?) {
        scope.launch {
            settings.update { it.copy(currentActivity = label) }
            _state.value.sessionId?.let { repo.setActivity(it, label, clock()) }
            _state.update { it.copy(activity = label) }
        }
    }

    fun updatePolicy(cooldownMinutes: Int, minSeverity: Severity) {
        policy.cooldownMs = cooldownMinutes * 60_000L
        policy.minSeverity = minSeverity
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val started = clock()
            if (_state.value.pausedReason == null) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "sampling failed; continuing", e)   // one bad reading must not end the session
                }
            }
            val wait = _state.value.effectiveIntervalS * 1000L - (clock() - started)
            delay(wait.coerceAtLeast(250L))
        }
    }

    private suspend fun tick() {
        val st = _state.value
        val sessionId = st.sessionId ?: return
        val reading = source.read()
        if (reading.wallTimeMs <= lastTimestamp) {
            Log.w(TAG, "non-increasing clock; sample skipped")
            return
        }
        lastTimestamp = reading.wallTimeMs

        val s = settings.current()
        val saving = s.batteryAwareSampling && reading.isCharging == false && (reading.batteryLevelPct ?: 100) <= 20
        val effective = if (saving) (st.configuredIntervalS * 2).coerceAtMost(60) else st.configuredIntervalS

        val exclude = isWorkloadRunning() || st.activity == "stress_test"
        val sampleId = repo.insertSample(sessionId, st.configuredIntervalS, reading, st.activity, excludeFromTraining = exclude)
        if (sampleId == null) {
            _state.update { it.copy(duplicatesSkipped = it.duplicatesSkipped + 1) }
            return
        }

        var result: DetectionResult? = null
        var micros: Long? = null
        val det = detector
        val raw = reading.toRawSample(st.configuredIntervalS)
        if (det != null && raw != null) {
            val t0 = System.nanoTime()
            result = det.process(raw, st.activity)
            micros = (System.nanoTime() - t0) / 1000
            val anomalyId = repo.saveDetection(sessionId, sampleId, det.features.windowSeconds, det.lastFeatures, result, st.activity)
            maybeAlert(anomalyId, result, simulated = false, notificationsEnabled = s.notificationsEnabled)
        }
        _state.update {
            it.copy(samplesTaken = it.samplesTaken + 1, lastReading = reading, lastResult = result ?: it.lastResult,
                lastInferenceMicros = micros ?: it.lastInferenceMicros, effectiveIntervalS = effective, batterySaving = saving,
                recentScores = result?.score?.let { sc -> (it.recentScores + (reading.wallTimeMs to sc)).takeLast(HISTORY) } ?: it.recentScores,
                recentMemRatio = (it.recentMemRatio + (reading.wallTimeMs to reading.memAvailBytes.toDouble() / reading.memTotalBytes)).takeLast(HISTORY))
        }
    }

    private suspend fun maybeAlert(anomalyId: Long?, r: DetectionResult, simulated: Boolean, notificationsEnabled: Boolean) {
        val decision = policy.decide(r.category, r.severity, r.timestampMs)
        if (decision != AlertPolicy.Decision.ALERT && !simulated) return
        if (r.severity == null) return
        val alertId = repo.createAlert(anomalyId, r, simulated)
        if (notificationsEnabled) {
            val title = (if (simulated) "[SIMULATION] " else "") + r.category.label
            if (notifier.notifyAlert(alertId, title, r.explanation, r.severity)) repo.alerts.markNotified(alertId)
        }
    }

    /**
     * Demonstration / test helper (Scenario E). Feeds a clearly SIMULATED stressed sequence through a
     * fresh detector (so the live session's feature window is untouched) and stores the result and
     * alert flagged as simulation. Never presented as a live detection.
     */
    fun injectSimulatedEpisode() {
        scope.launch {
            val sessionId = _state.value.sessionId ?: return@launch
            val base = _state.value.lastReading ?: source.read()
            val sim = AnomalyDetector(models.bundleOrNull)
            val interval = 5
            val start = clock() - 23 * interval * 1000L
            var last: DetectionResult? = null
            for (i in 0 until 24) {
                val frac = (0.35 - 0.33 * i / 23.0).coerceAtLeast(0.02)
                val raw = RawSample(
                    timestampMs = start + i * interval * 1000L, intervalS = interval,
                    memTotalBytes = base.memTotalBytes, memAvailBytes = (base.memTotalBytes * frac).toLong(),
                    memThresholdBytes = base.memThresholdBytes, lowMemory = base.memTotalBytes * frac < base.memThresholdBytes,
                    batteryLevelPct = base.batteryLevelPct ?: 50, isCharging = false,
                    thermalStatus = minOf(4, i / 6), thermalHeadroom = null,
                    batteryTempC = 33.0 + i * 0.4, storageTotalBytes = base.storageTotalBytes, storageAvailBytes = base.storageAvailBytes,
                )
                last = sim.process(raw, null)
            }
            val r = last ?: return@launch
            val id = repo.saveDetection(sessionId, null, sim.features.windowSeconds, null, r, null, simulated = true)
            maybeAlert(id, r, simulated = true, notificationsEnabled = settings.current().notificationsEnabled)
        }
    }

    private fun DeviceReading.toRawSample(intervalS: Int): RawSample? {
        val battery = batteryLevelPct ?: return null
        return try {
            RawSample(wallTimeMs, intervalS, memTotalBytes, memAvailBytes, memThresholdBytes, lowMemory, battery,
                isCharging ?: false, thermalStatus, thermalHeadroom, batteryTempC, storageTotalBytes, storageAvailBytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "implausible reading skipped: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MonitoringEngine"
        const val HISTORY = 180
    }
}
