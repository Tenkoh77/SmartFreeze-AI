package com.buse.smartfreeze.domain

import kotlin.math.min

/** Summary of the user's recorded normal data (excludes simulated and controlled-workload samples). */
data class BaselineStats(
    val samples: Int = 0,
    val days: Int = 0,
    /** Approximate monitored time: sum of the sampling interval of each recorded sample. */
    val monitoredSeconds: Long = 0,
) {
    val monitoredHours: Double get() = monitoredSeconds / 3600.0
}

/**
 * When the phone has collected enough of its OWN normal data to train a personal model, and when
 * an existing personal model should be retrained.
 *
 * Full personal model: >= 6 h monitored, >= 2,000 samples and >= 3 different days, so the baseline
 * covers several kinds of normal use rather than one afternoon.
 * Quick demo baseline (research mode only, labelled "demo-quality"): >= 30 min and >= 150 samples.
 * Retraining: when the model is >= 7 days old and >= 500 new samples have been recorded since.
 */
class TrainingPolicy(
    val minHours: Double = 6.0,
    val minSamples: Int = 2000,
    val minDays: Int = 3,
    val demoMinMinutes: Double = 30.0,
    val demoMinSamples: Int = 150,
    val retrainAfterDays: Int = 7,
    val retrainMinNewSamples: Int = 500,
) {
    /** Progress towards a full personal model in [0, 1]: the least-complete of the three criteria. */
    fun progress(s: BaselineStats): Double = minOf(
        min(1.0, s.monitoredHours / minHours),
        min(1.0, s.samples.toDouble() / minSamples),
        min(1.0, s.days.toDouble() / minDays),
    )

    fun isReady(s: BaselineStats): Boolean =
        s.monitoredHours >= minHours && s.samples >= minSamples && s.days >= minDays

    fun isDemoReady(s: BaselineStats): Boolean =
        s.monitoredSeconds >= demoMinMinutes * 60 && s.samples >= demoMinSamples

    fun shouldRetrain(trainedAtMs: Long, newSamplesSince: Int, nowMs: Long): Boolean =
        nowMs - trainedAtMs >= retrainAfterDays * 86_400_000L && newSamplesSince >= retrainMinNewSamples

    /** Human-readable list of what is still missing, e.g. "2.4 more hours", "1 more day". */
    fun remaining(s: BaselineStats): List<String> = buildList {
        if (s.monitoredHours < minHours) add("%.1f more hours of monitoring".format(java.util.Locale.ROOT, minHours - s.monitoredHours))
        if (s.samples < minSamples) add("${minSamples - s.samples} more samples")
        if (s.days < minDays) add("data from ${minDays - s.days} more day" + if (minDays - s.days == 1) "" else "s")
    }
}
