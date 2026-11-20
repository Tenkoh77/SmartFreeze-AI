package com.buse.smartfreeze.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 1. Device information (single row, refreshed at app start). */
@Entity(tableName = "device_info")
data class DeviceInfoEntity(
    @PrimaryKey val id: Int = 1,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val totalRamBytes: Long,
    val thermalHeadroomSupported: Boolean?,
    val updatedAtMs: Long,
)

/** 2. Monitoring sessions. */
@Entity(tableName = "sessions", indices = [Index("startedAtMs")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val intervalS: Int,
    val mode: String,                 // "foreground" or "background"
    val isResearch: Boolean,
    val note: String? = null,
)

/** 3. Performance samples. Nullable columns = measurement unavailable (never stored as 0). */
@Entity(
    tableName = "samples",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "timestampMs"], unique = true), Index("timestampMs")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    val intervalS: Int,
    val memTotalBytes: Long,
    val memAvailBytes: Long,
    val memThresholdBytes: Long,
    val lowMemory: Boolean,
    val batteryLevelPct: Int?,
    val isCharging: Boolean?,
    val thermalStatus: Int?,
    val thermalHeadroom: Double?,
    val batteryTempC: Double?,
    val batteryHealth: Int?,
    val storageTotalBytes: Long?,
    val storageAvailBytes: Long?,
    val appPssKb: Long?,
    val appCpuMs: Long?,
    val activityLabel: String?,
    /** Bit flags of which optional metrics were available, see [Availability]. */
    val availability: Int,
    val isSimulated: Boolean = false,
    /** True for samples recorded during a controlled workload / stress test: never used as "normal" training data. (v2) */
    @ColumnInfo(defaultValue = "0") val excludeFromTraining: Boolean = false,
)

object Availability {
    const val THERMAL_STATUS = 1
    const val THERMAL_HEADROOM = 2
    const val BATTERY_TEMP = 4
    const val STORAGE = 8
    const val APP_STATS = 16
    const val BATTERY_LEVEL = 32
}

/** 4. Derived feature windows (one per detected sample; NaN stored as empty field). */
@Entity(
    tableName = "feature_windows",
    foreignKeys = [ForeignKey(entity = SampleEntity::class, parentColumns = ["id"], childColumns = ["sampleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sampleId"], unique = true), Index("sessionId")],
)
data class FeatureWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sampleId: Long,
    val sessionId: Long,
    val timestampMs: Long,
    val windowSeconds: Int,
    val featuresCsv: String,
)

/** 5. Activity labels: periods of a session labelled by the user in research mode. */
@Entity(
    tableName = "activity_labels",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class ActivityLabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val label: String,
    val startMs: Long,
    val endMs: Long? = null,
)

/** 6. Detection results that were not NORMAL (anomalies and rule-based pressure). */
@Entity(
    tableName = "anomalies",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("timestampMs")],
)
data class AnomalyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val sampleId: Long?,
    val timestampMs: Long,
    val source: String,               // DetectionSource
    val modelName: String?,
    val modelVersion: String?,
    val score: Double?,
    val threshold: Double?,
    val isAnomalous: Boolean,
    val category: String,             // AlertCategory
    val severity: String?,            // Severity
    val explanation: String,
    val deviations: String,           // "feature:value:median:z;..."
    val recommendations: String,      // newline separated
    val activityLabel: String?,
    val isSimulated: Boolean = false,
)

/** 7. Slowdown event labels (ground truth for the early-warning research). */
@Entity(tableName = "slowdown_events", indices = [Index("sessionId"), Index("timestampMs")])
data class SlowdownEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val timestampMs: Long,
    val eventType: String,            // latency_probe | frame | manual
    val latencyMs: Long?,
    val source: String,               // research_probe | user
    val note: String? = null,
)

/** 8. Alert history. */
@Entity(tableName = "alerts", indices = [Index("timestampMs"), Index("acknowledged")])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val anomalyId: Long?,
    val timestampMs: Long,
    val category: String,
    val severity: String,
    val title: String,
    val message: String,
    val affectedIndicators: String,
    val recommendation: String,
    val isPrediction: Boolean,
    val acknowledged: Boolean = false,
    val notified: Boolean = false,
    val isSimulated: Boolean = false,
)

/** 9. Model metadata (one row per model version seen). */
@Entity(tableName = "model_metadata")
data class ModelMetadataEntity(
    @PrimaryKey val modelVersion: String,
    val createdUtc: String,
    val loadedAtMs: Long,
    val syntheticTrainingData: Boolean,
    val windowSeconds: Int,
    val activityModels: String,
    val generalThreshold: Double,
    val nTrain: Int,
)

/** 10. User settings (single row). */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val onboardingDone: Boolean = false,
    val intervalS: Int = 10,
    val notificationsEnabled: Boolean = true,
    val minAlertSeverity: String = "MEDIUM",
    val cooldownMinutes: Int = 10,
    val retentionDays: Int = 30,
    val themeMode: String = "SYSTEM",       // SYSTEM | LIGHT | DARK
    val researchMode: Boolean = false,
    val currentActivity: String? = null,     // manual activity label used for new samples
    val batteryAwareSampling: Boolean = true,
)
