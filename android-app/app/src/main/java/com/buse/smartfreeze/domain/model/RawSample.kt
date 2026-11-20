package com.buse.smartfreeze.domain.model

/**
 * One measurement taken by the monitoring engine, independent of Android and Room.
 * Optional measurements are null when the device/API does not provide them; they are
 * never replaced by zero (see feature_schema.json "missing_rule").
 */
data class RawSample(
    val timestampMs: Long,
    val intervalS: Int,
    val memTotalBytes: Long,
    val memAvailBytes: Long,
    val memThresholdBytes: Long,
    val lowMemory: Boolean,
    val batteryLevelPct: Int,
    val isCharging: Boolean,
    val thermalStatus: Int?,
    val thermalHeadroom: Double?,
    val batteryTempC: Double?,
    val storageTotalBytes: Long?,
    val storageAvailBytes: Long?,
) {
    init {
        require(memTotalBytes > 0) { "memTotalBytes must be positive" }
        require(memAvailBytes in 0..memTotalBytes) { "memAvailBytes out of range" }
        require(intervalS > 0) { "intervalS must be positive" }
        require(batteryLevelPct in 0..100) { "batteryLevelPct out of range" }
    }

    val memAvailRatio: Double get() = memAvailBytes.toDouble() / memTotalBytes.toDouble()
    val memMarginRatio: Double get() = (memAvailBytes.toDouble() - memThresholdBytes.toDouble()) / memTotalBytes.toDouble()
}
