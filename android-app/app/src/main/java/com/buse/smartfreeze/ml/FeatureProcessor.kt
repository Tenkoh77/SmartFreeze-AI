package com.buse.smartfreeze.ml

import com.buse.smartfreeze.domain.model.RawSample
import kotlin.math.sqrt

/**
 * Streaming feature extraction for ONE monitoring session.
 *
 * Line-by-line port of machine-learning/src/smartfreeze_ml/features/engineering.py
 * (compute_session_features). Each call to [next] uses only the current sample and
 * samples already seen, so there is no access to future information.
 * Create a new instance (or call [reset]) at the start of every session.
 *
 * Missing measurements produce Double.NaN; imputation happens in the model.
 */
class FeatureProcessor(val windowSeconds: Int) {
    init { require(windowSeconds > 0) { "windowSeconds must be positive" } }

    private class Entry(val t: Long, val ratio: Double, val thermal: Double, val pressure: Boolean)

    private val window = ArrayDeque<Entry>()
    private var prevT: Long? = null

    fun reset() {
        window.clear()
        prevT = null
    }

    fun next(s: RawSample): DoubleArray {
        prevT?.let { require(s.timestampMs > it) { "samples must be strictly increasing in time" } }
        val windowMs = windowSeconds * 1000L
        val ratio = s.memAvailRatio
        val margin = s.memMarginRatio
        val low = if (s.lowMemory) 1.0 else 0.0
        val thermal = s.thermalStatus?.toDouble() ?: Double.NaN
        val headroom = s.thermalHeadroom ?: Double.NaN
        val batt = s.batteryLevelPct / 100.0
        val charging = if (s.isCharging) 1.0 else 0.0
        val btemp = s.batteryTempC ?: Double.NaN
        val storage = if (s.storageTotalBytes != null && s.storageAvailBytes != null && s.storageTotalBytes > 0)
            s.storageAvailBytes.toDouble() / s.storageTotalBytes.toDouble() else Double.NaN

        window.addLast(Entry(s.timestampMs, ratio, thermal, isPressure(low, margin, thermal)))
        while (window.isNotEmpty() && window.first().t <= s.timestampMs - windowMs) window.removeFirst()

        val n = window.size
        var sum = 0.0
        for (e in window) sum += e.ratio
        val mean = sum / n
        var ss = 0.0
        for (e in window) ss += (e.ratio - mean) * (e.ratio - mean)
        val std = sqrt(ss / n)
        val slope = slopePerMinute()
        var minThermal = Double.NaN
        for (e in window) if (!e.thermal.isNaN() && (minThermal.isNaN() || e.thermal < minThermal)) minThermal = e.thermal
        val tDelta = if (thermal.isNaN()) Double.NaN else thermal - minThermal
        val pFrac = window.count { it.pressure }.toDouble() / n
        val gap = prevT?.let { (s.timestampMs - it) / 1000.0 } ?: Double.NaN
        val expected = maxOf(1, windowSeconds / s.intervalS)
        val completeness = minOf(1.0, n.toDouble() / expected)

        prevT = s.timestampMs
        return doubleArrayOf(ratio, margin, low, thermal, headroom, batt, charging, btemp, storage,
            mean, std, slope, tDelta, pFrac, gap, completeness)
    }

    private fun slopePerMinute(): Double {
        val n = window.size
        if (n < 2) return 0.0
        val tNow = window.last().t
        var mx = 0.0
        var my = 0.0
        for (e in window) { mx += (e.t - tNow) / 60000.0; my += e.ratio }
        mx /= n; my /= n
        var sxx = 0.0
        var sxy = 0.0
        for (e in window) {
            val dx = (e.t - tNow) / 60000.0 - mx
            sxx += dx * dx
            sxy += dx * (e.ratio - my)
        }
        return if (sxx == 0.0) 0.0 else sxy / sxx
    }

    companion object {
        fun isPressure(low: Double, margin: Double, thermal: Double): Boolean =
            low == 1.0 || margin < FeatureSchema.PRESSURE_MARGIN ||
                (!thermal.isNaN() && thermal >= FeatureSchema.PRESSURE_THERMAL)
    }
}
