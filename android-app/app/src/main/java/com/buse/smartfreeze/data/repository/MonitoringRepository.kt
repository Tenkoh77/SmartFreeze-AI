package com.buse.smartfreeze.data.repository

import androidx.room.withTransaction
import com.buse.smartfreeze.data.collector.DeviceReading
import com.buse.smartfreeze.data.db.ActivityLabelEntity
import com.buse.smartfreeze.data.db.AlertEntity
import com.buse.smartfreeze.data.db.AnomalyEntity
import com.buse.smartfreeze.data.db.AppDatabase
import com.buse.smartfreeze.data.db.Availability
import com.buse.smartfreeze.data.db.FeatureWindowEntity
import com.buse.smartfreeze.data.db.SampleEntity
import com.buse.smartfreeze.data.db.SessionEntity
import com.buse.smartfreeze.data.db.SlowdownEventEntity
import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.DetectionResult
import com.buse.smartfreeze.ml.FeatureSchema

/** All writes and reads of monitoring data go through this class (single source of truth). */
class MonitoringRepository(private val db: AppDatabase) {

    val sessions get() = db.sessions()
    val samples get() = db.samples()
    val anomalies get() = db.anomalies()
    val alerts get() = db.alerts()
    val slowdownEvents get() = db.slowdownEvents()
    val deviceInfo get() = db.deviceInfo()
    val modelMetadata get() = db.modelMetadata()

    suspend fun startSession(intervalS: Int, mode: String, research: Boolean, now: Long): Long {
        db.sessions().closeDangling()      // a previous process may have died mid-session
        return db.sessions().insert(SessionEntity(startedAtMs = now, intervalS = intervalS, mode = mode, isResearch = research))
    }

    suspend fun endSession(id: Long, now: Long) {
        db.activityLabels().closeOpen(id, now)
        db.sessions().end(id, now)
    }

    suspend fun setActivity(sessionId: Long, label: String?, now: Long) = db.withTransaction {
        db.activityLabels().closeOpen(sessionId, now)
        if (label != null) db.activityLabels().insert(ActivityLabelEntity(sessionId = sessionId, label = label, startMs = now))
    }

    /** Returns the new sample id, or null if it was a duplicate (same session and timestamp). */
    suspend fun insertSample(
        sessionId: Long, intervalS: Int, r: DeviceReading, activity: String?, simulated: Boolean = false,
        excludeFromTraining: Boolean = false,
    ): Long? {
        var flags = 0
        if (r.thermalStatus != null) flags = flags or Availability.THERMAL_STATUS
        if (r.thermalHeadroom != null) flags = flags or Availability.THERMAL_HEADROOM
        if (r.batteryTempC != null) flags = flags or Availability.BATTERY_TEMP
        if (r.storageAvailBytes != null) flags = flags or Availability.STORAGE
        if (r.appPssKb != null) flags = flags or Availability.APP_STATS
        if (r.batteryLevelPct != null) flags = flags or Availability.BATTERY_LEVEL
        val id = db.samples().insert(
            SampleEntity(
                sessionId = sessionId, timestampMs = r.wallTimeMs, intervalS = intervalS,
                memTotalBytes = r.memTotalBytes, memAvailBytes = r.memAvailBytes, memThresholdBytes = r.memThresholdBytes,
                lowMemory = r.lowMemory, batteryLevelPct = r.batteryLevelPct, isCharging = r.isCharging,
                thermalStatus = r.thermalStatus, thermalHeadroom = r.thermalHeadroom, batteryTempC = r.batteryTempC,
                batteryHealth = r.batteryHealth, storageTotalBytes = r.storageTotalBytes, storageAvailBytes = r.storageAvailBytes,
                appPssKb = r.appPssKb, appCpuMs = r.appCpuMs, activityLabel = activity, availability = flags,
                isSimulated = simulated, excludeFromTraining = excludeFromTraining,
            ),
        )
        return id.takeIf { it > 0 }
    }

    /** Persists features + (if not NORMAL) the anomaly. Returns the anomaly id or null. */
    suspend fun saveDetection(
        sessionId: Long, sampleId: Long?, windowSeconds: Int, features: DoubleArray?, result: DetectionResult,
        activity: String?, simulated: Boolean = false,
    ): Long? = db.withTransaction {
        if (sampleId != null && features != null) {
            db.features().insert(
                FeatureWindowEntity(sampleId = sampleId, sessionId = sessionId, timestampMs = result.timestampMs,
                    windowSeconds = windowSeconds, featuresCsv = encodeFeatures(features)),
            )
        }
        if (result.category == AlertCategory.NORMAL) return@withTransaction null
        db.anomalies().insert(
            AnomalyEntity(
                sessionId = sessionId, sampleId = sampleId, timestampMs = result.timestampMs, source = result.source.name,
                modelName = result.modelName, modelVersion = result.modelVersion, score = result.score,
                threshold = result.threshold, isAnomalous = result.isAnomalous, category = result.category.name,
                severity = result.severity?.name, explanation = result.explanation,
                deviations = result.deviations.joinToString(";") {
                    "${it.featureName}:${fmt(it.value)}:${fmt(it.baselineMedian)}:${fmt(it.robustZ)}"
                },
                recommendations = result.recommendations.joinToString("\n"), activityLabel = activity, isSimulated = simulated,
            ),
        )
    }

    suspend fun createAlert(anomalyId: Long?, r: DetectionResult, simulated: Boolean): Long {
        val sev = requireNotNull(r.severity)
        val prefix = if (simulated) "[SIMULATION] " else ""
        return db.alerts().insert(
            AlertEntity(
                anomalyId = anomalyId, timestampMs = r.timestampMs, category = r.category.name, severity = sev.name,
                title = prefix + r.category.label, message = r.explanation,
                affectedIndicators = r.deviations.joinToString(", ") { it.featureName },
                recommendation = r.recommendations.firstOrNull() ?: "", isPrediction = r.category.isPrediction,
                isSimulated = simulated,
            ),
        )
    }

    suspend fun recordSlowdown(sessionId: Long?, now: Long, type: String, latencyMs: Long?, source: String, note: String? = null) =
        db.slowdownEvents().insert(SlowdownEventEntity(sessionId = sessionId, timestampMs = now, eventType = type,
            latencyMs = latencyMs, source = source, note = note))

    /** Retention cleanup: removes data older than [days]. Returns number of samples removed. */
    suspend fun applyRetention(days: Int, now: Long): Int = db.withTransaction {
        val before = now - days * 86_400_000L
        val removed = db.samples().deleteOlderThan(before)
        db.sessions().deleteOlderThan(before)
        db.alerts().deleteOlderThan(before)
        db.maintenance().deleteEventsOlderThan(before)
        removed
    }

    suspend fun deleteAllMonitoringData() = db.withTransaction {
        db.maintenance().clearSessions()
        db.maintenance().clearAlerts()
        db.maintenance().clearSlowdownEvents()
    }

    companion object {
        fun encodeFeatures(x: DoubleArray): String = x.joinToString(",") { if (it.isFinite()) it.toString() else "" }
        fun featureHeader(): String = FeatureSchema.NAMES.joinToString(",")
        private fun fmt(v: Double) = if (v.isFinite()) "%.4f".format(java.util.Locale.ROOT, v) else "NA"
    }
}
