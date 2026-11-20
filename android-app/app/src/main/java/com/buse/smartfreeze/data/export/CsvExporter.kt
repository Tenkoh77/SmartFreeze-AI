package com.buse.smartfreeze.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.buse.smartfreeze.data.db.AppDatabase
import com.buse.smartfreeze.data.db.SampleEntity
import com.buse.smartfreeze.data.repository.MonitoringRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes CSV files in EXACTLY the format read by machine-learning/src/smartfreeze_ml/preprocessing
 * (samples) and research/slowdown.py (events). Files go to the app's private cache and leave the
 * device only when the user chooses a destination in the Android share sheet.
 */
class CsvExporter(private val context: Context, private val db: AppDatabase) {

    data class ExportResult(val files: List<File>, val sampleRows: Int)

    suspend fun exportAll(): ExportResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val samplesFile = File(dir, "samples_$stamp.csv")
        var rows = 0
        samplesFile.bufferedWriter().use { w ->
            w.appendLine(SAMPLE_HEADER)
            var offset = 0
            while (true) {
                val page = db.samples().page(PAGE, offset)
                if (page.isEmpty()) break
                page.forEach { w.writeSample(it) }
                rows += page.size
                offset += page.size
            }
        }
        val eventsFile = File(dir, "slowdown_events_$stamp.csv")
        eventsFile.bufferedWriter().use { w ->
            w.appendLine("session_id,timestamp_ms,event_type,latency_ms,source,is_simulated")
            db.slowdownEvents().all().forEach {
                w.appendLine(listOf(it.sessionId ?: "", it.timestampMs, it.eventType, it.latencyMs ?: "", it.source, 0).joinToString(","))
            }
        }
        val anomaliesFile = File(dir, "detections_$stamp.csv")
        anomaliesFile.bufferedWriter().use { w ->
            w.appendLine("session_id,timestamp_ms,source,model_name,model_version,score,threshold,is_anomalous,category,severity,activity_label,is_simulated,deviations")
            var offset = 0
            while (true) {
                val page = db.anomalies().page(PAGE, offset)
                if (page.isEmpty()) break
                page.forEach {
                    w.appendLine(listOf(it.sessionId, it.timestampMs, it.source, it.modelName ?: "", it.modelVersion ?: "",
                        it.score ?: "", it.threshold ?: "", b(it.isAnomalous), it.category, it.severity ?: "",
                        csv(it.activityLabel), b(it.isSimulated), csv(it.deviations)).joinToString(","))
                }
                offset += page.size
            }
        }
        val featuresFile = File(dir, "features_$stamp.csv")
        featuresFile.bufferedWriter().use { w ->
            w.appendLine("session_id,sample_id,timestamp_ms,window_seconds," + MonitoringRepository.featureHeader())
            var offset = 0
            while (true) {
                val page = db.features().page(PAGE, offset)
                if (page.isEmpty()) break
                page.forEach { w.appendLine("${it.sessionId},${it.sampleId},${it.timestampMs},${it.windowSeconds},${it.featuresCsv}") }
                offset += page.size
            }
        }
        ExportResult(listOf(samplesFile, eventsFile, anomaliesFile, featuresFile), rows)
    }

    /** Intent for the system share sheet; the user picks where the files go. */
    fun shareIntent(files: List<File>): Intent {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "SmartFreeze AI export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun Writer.writeSample(s: SampleEntity) {
        appendLine(listOf(
            s.sessionId, s.timestampMs, s.intervalS, s.memTotalBytes, s.memAvailBytes, s.memThresholdBytes,
            b(s.lowMemory), s.batteryLevelPct ?: "", s.isCharging?.let { b(it) } ?: "", csv(s.activityLabel),
            s.thermalStatus ?: "", s.thermalHeadroom ?: "", s.batteryTempC ?: "", s.storageTotalBytes ?: "",
            s.storageAvailBytes ?: "", s.appPssKb ?: "", "", b(s.isSimulated),
        ).joinToString(","))
    }

    companion object {
        private const val PAGE = 2000
        const val SAMPLE_HEADER = ExportSchema.SAMPLE_HEADER
        private fun b(v: Boolean) = if (v) 1 else 0
        private fun csv(v: String?): String = when {
            v == null -> ""
            v.any { it == ',' || it == '"' || it == '\n' } -> "\"" + v.replace("\"", "\"\"") + "\""
            else -> v
        }
    }
}
