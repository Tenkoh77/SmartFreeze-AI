package com.buse.smartfreeze.data.collector

/**
 * One reading of every metric SmartFreeze AI can legitimately obtain without root.
 * A null field means "not available on this device / Android version"; it is never faked.
 */
data class DeviceReading(
    val wallTimeMs: Long,
    val memTotalBytes: Long,
    val memAvailBytes: Long,
    val memThresholdBytes: Long,
    val lowMemory: Boolean,
    val thermalStatus: Int?,
    val thermalHeadroom: Double?,
    val batteryLevelPct: Int?,
    val isCharging: Boolean?,
    val batteryTempC: Double?,
    val batteryHealth: Int?,
    val storageTotalBytes: Long?,
    val storageAvailBytes: Long?,
    /** SmartFreeze's own proportional set size (KB): overhead measurement only, not a model feature. */
    val appPssKb: Long?,
    /** CPU time consumed by SmartFreeze's own process since start (ms): overhead measurement only. */
    val appCpuMs: Long?,
)

/**
 * Abstraction over Android system services (dependency inversion), so the monitoring engine
 * can be unit-tested with simulated readings.
 */
interface MetricsSource {
    fun read(): DeviceReading
}
