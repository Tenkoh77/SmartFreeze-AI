package com.buse.smartfreeze.domain

import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.FeatureDeviation
import com.buse.smartfreeze.ml.Baseline
import com.buse.smartfreeze.ml.FeatureSchema
import kotlin.math.abs
import kotlin.math.max

/**
 * Generates human-readable explanations by comparing current feature values with the
 * baseline (training-data quartiles) exported with the model.
 *
 * Method: robust z-score  z = (x - median) / max(IQR / 1.349, floor)
 * (IQR / 1.349 estimates the standard deviation for normally distributed data and is
 * resistant to outliers). Features with |z| >= [minAbsZ] are reported, largest first.
 *
 * IMPORTANT: this is a DESCRIPTIVE comparison with the user's normal readings. It is not
 * a mathematically faithful attribution of the Isolation Forest score (such as SHAP),
 * and it never claims to have identified the cause of a slowdown. The UI says so.
 */
class ExplanationEngine(private val minAbsZ: Double = 2.0, private val maxReported: Int = 3) {

    // Minimum spread per feature so that near-constant baselines do not create huge z-scores.
    private val floor = DoubleArray(FeatureSchema.SIZE) { 0.01 }.also {
        it[FeatureSchema.THERMAL_STATUS] = 0.5
        it[FeatureSchema.THERMAL_DELTA_W] = 0.5
        it[FeatureSchema.LOW_MEMORY] = 0.5
        it[FeatureSchema.IS_CHARGING] = 0.5
        it[FeatureSchema.BATTERY_TEMP_C] = 1.0
        it[FeatureSchema.SAMPLE_GAP_S] = 2.0
        it[FeatureSchema.THERMAL_HEADROOM] = 0.05
        it[FeatureSchema.MEM_RATIO_SLOPE_W] = 0.02
    }

    // Features worth explaining to a user (bookkeeping features like completeness are excluded).
    private val explainable = setOf(
        FeatureSchema.MEM_AVAIL_RATIO, FeatureSchema.MEM_MARGIN_RATIO, FeatureSchema.LOW_MEMORY,
        FeatureSchema.THERMAL_STATUS, FeatureSchema.THERMAL_HEADROOM, FeatureSchema.BATTERY_TEMP_C,
        FeatureSchema.STORAGE_FREE_RATIO, FeatureSchema.MEM_RATIO_STD_W, FeatureSchema.MEM_RATIO_SLOPE_W,
        FeatureSchema.THERMAL_DELTA_W, FeatureSchema.PRESSURE_FRACTION_W,
    )

    fun deviations(x: DoubleArray, baseline: Baseline): List<FeatureDeviation> =
        explainable.mapNotNull { i ->
            val v = x[i]
            if (v.isNaN()) return@mapNotNull null
            val spread = max((baseline.q75[i] - baseline.q25[i]) / 1.349, floor[i])
            val z = (v - baseline.median[i]) / spread
            if (abs(z) < minAbsZ) null else FeatureDeviation(i, FeatureSchema.NAMES[i], v, baseline.median[i], z)
        }.sortedByDescending { abs(it.robustZ) }.take(maxReported)

    fun describe(d: FeatureDeviation): String {
        val up = d.robustZ > 0
        return when (d.featureIndex) {
            FeatureSchema.MEM_AVAIL_RATIO, FeatureSchema.MEM_MARGIN_RATIO ->
                if (up) "more available memory than usual" else "an unusually low level of available memory"
            FeatureSchema.LOW_MEMORY -> "Android reporting a low-memory state"
            FeatureSchema.THERMAL_STATUS, FeatureSchema.THERMAL_DELTA_W ->
                if (up) "an increase in thermal pressure" else "lower thermal pressure than usual"
            FeatureSchema.THERMAL_HEADROOM -> if (up) "reduced thermal headroom" else "more thermal headroom than usual"
            FeatureSchema.BATTERY_TEMP_C -> if (up) "a higher battery temperature than usual" else "a lower battery temperature than usual"
            FeatureSchema.STORAGE_FREE_RATIO -> if (up) "more free storage than usual" else "less free storage than usual"
            FeatureSchema.MEM_RATIO_STD_W -> "unusually large fluctuations in available memory"
            FeatureSchema.MEM_RATIO_SLOPE_W ->
                if (up) "available memory recovering quickly" else "available memory falling faster than usual"
            FeatureSchema.PRESSURE_FRACTION_W -> "a longer period of resource pressure than usual"
            else -> d.featureName
        }
    }

    fun explain(category: AlertCategory, devs: List<FeatureDeviation>, ruleBased: Boolean): String {
        if (category == AlertCategory.NORMAL) {
            return if (ruleBased) "No rule-based resource-pressure condition is active."
            else "Current readings are consistent with your learned normal pattern."
        }
        val who = if (ruleBased) "A rule-based check (not machine learning)" else "SmartFreeze AI's anomaly model"
        val parts = devs.map { describe(it) }.distinct()
        val what = when (parts.size) {
            0 -> "a combination of readings that differs from your normal pattern"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
        return "$who detected $what. These readings differ from your device's normal pattern; " +
            "this does not by itself mean the phone will freeze."
    }

    fun recommendations(devs: List<FeatureDeviation>, x: DoubleArray): List<String> {
        val recs = LinkedHashSet<String>()
        val idx = devs.map { it.featureIndex }.toSet()
        val memLow = idx.any { it in setOf(FeatureSchema.MEM_AVAIL_RATIO, FeatureSchema.MEM_MARGIN_RATIO,
            FeatureSchema.LOW_MEMORY, FeatureSchema.MEM_RATIO_SLOPE_W) } || x[FeatureSchema.LOW_MEMORY] == 1.0
        val hot = idx.any { it in setOf(FeatureSchema.THERMAL_STATUS, FeatureSchema.THERMAL_DELTA_W,
            FeatureSchema.THERMAL_HEADROOM, FeatureSchema.BATTERY_TEMP_C) }
        if (memLow) recs += "Close applications you no longer need."
        if (hot) recs += "Let the phone cool down: pause heavy apps or games, and avoid charging while it is hot."
        val storage = x[FeatureSchema.STORAGE_FREE_RATIO]
        if (!storage.isNaN() && storage < 0.10) recs += "Storage is nearly full. Remove unused files or apps."
        if (FeatureSchema.PRESSURE_FRACTION_W in idx) recs += "If problems persist, restart the phone when convenient."
        recs += "If lag began after an app update, review recently updated apps."
        return recs.toList()
    }
}
