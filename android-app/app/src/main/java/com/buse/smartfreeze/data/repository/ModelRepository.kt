package com.buse.smartfreeze.data.repository

import android.content.Context
import android.util.Log
import com.buse.smartfreeze.data.db.ModelMetadataDao
import com.buse.smartfreeze.data.db.ModelMetadataEntity
import com.buse.smartfreeze.ml.ModelBundle
import com.buse.smartfreeze.ml.ModelBundleWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

sealed interface ModelStatus {
    data object Loading : ModelStatus
    /** No usable model yet: baseline-collection ("learning your phone") state with rule-based checks. */
    data object NotAvailable : ModelStatus
    data class Invalid(val reason: String) : ModelStatus
    data class Ready(val bundle: ModelBundle, val loadMillis: Long) : ModelStatus
}

/**
 * Chooses and loads the anomaly model, in this order:
 *  1. the PERSONAL model trained on this phone (files/model/personal_model.json);
 *  2. a model shipped in the APK (assets/model/smartfreeze_model.json), but ONLY if it was trained
 *     on real recorded data (a "team starter model"). A model trained on synthetic test data is
 *     never used at runtime;
 *  3. otherwise NotAvailable: the app collects the baseline and uses clearly labelled rule-based checks.
 * A missing or malformed file never crashes the app.
 */
class ModelRepository(private val context: Context, private val metadataDao: ModelMetadataDao) {
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()
    private val mutex = Mutex()

    private val personalFile get() = File(File(context.filesDir, "model"), PERSONAL_FILE)

    val bundleOrNull: ModelBundle? get() = (status.value as? ModelStatus.Ready)?.bundle

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { _status.value = loadInternal() } }

    private suspend fun loadInternal(): ModelStatus {
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000

        val f = personalFile
        if (f.exists()) {
            try {
                val b = ModelBundle.parse(f.readText())
                record(b)
                Log.i(TAG, "personal model ${b.modelVersion} loaded in ${ms()} ms")
                return ModelStatus.Ready(b, ms())
            } catch (e: Exception) {
                // Keep the damaged file for diagnosis but stop using it; fall through to the shipped model.
                Log.e(TAG, "personal model rejected", e)
                f.renameTo(File(f.parentFile, "$PERSONAL_FILE.rejected"))
            }
        }
        return try {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val b = ModelBundle.parse(text)
            if (b.syntheticTrainingData) {
                Log.i(TAG, "shipped model is synthetic (test only): not used at runtime")
                ModelStatus.NotAvailable
            } else {
                record(b)
                ModelStatus.Ready(b, ms())
            }
        } catch (e: FileNotFoundException) {
            ModelStatus.NotAvailable
        } catch (e: Exception) {
            Log.e(TAG, "shipped model rejected", e)
            ModelStatus.Invalid(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Saves a model trained on this phone and makes it the active model. The JSON is re-parsed with the
     * same validating parser before it replaces the old file, and written atomically (temp file + rename).
     */
    suspend fun installPersonal(bundle: ModelBundle) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val json = ModelBundleWriter.toJson(bundle)
            val parsed = ModelBundle.parse(json)
            val dir = File(context.filesDir, "model").apply { mkdirs() }
            val tmp = File(dir, "$PERSONAL_FILE.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(File(dir, PERSONAL_FILE))) {
                tmp.copyTo(File(dir, PERSONAL_FILE), overwrite = true); tmp.delete()
            }
            record(parsed)
            _status.value = ModelStatus.Ready(parsed, 0)
        }
    }

    /** Removes the personal model; the app returns to the shipped model or to baseline collection. */
    suspend fun deletePersonal() {
        withContext(Dispatchers.IO) { personalFile.delete() }
        load()
    }

    private suspend fun record(b: ModelBundle) {
        metadataDao.upsert(
            ModelMetadataEntity(
                modelVersion = b.modelVersion, createdUtc = b.createdUtc, loadedAtMs = System.currentTimeMillis(),
                syntheticTrainingData = b.syntheticTrainingData, windowSeconds = b.windowSeconds,
                activityModels = b.activityModels.keys.sorted().joinToString(","),
                generalThreshold = b.general.scoreThreshold, nTrain = b.general.nTrain,
            ),
        )
    }

    companion object {
        private const val TAG = "ModelRepository"
        const val ASSET_PATH = "model/smartfreeze_model.json"
        const val PERSONAL_FILE = "personal_model.json"
    }
}
