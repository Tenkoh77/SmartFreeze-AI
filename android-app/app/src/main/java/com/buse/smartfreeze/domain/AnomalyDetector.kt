package com.buse.smartfreeze.domain

import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.DetectionResult
import com.buse.smartfreeze.domain.model.DetectionSource
import com.buse.smartfreeze.domain.model.RawSample
import com.buse.smartfreeze.ml.Baseline
import com.buse.smartfreeze.ml.FeatureProcessor
import com.buse.smartfreeze.ml.FeatureSchema
import com.buse.smartfreeze.ml.ModelBundle

/**
 * Per-session detection pipeline: RawSample -> features -> Isolation Forest (or cold-start
 * rules) -> category/severity -> explanation and recommendations.
 *
 * Cold start: when [bundle] is null, no ML result is produced. Only the clearly labelled
 * rule-based SUSTAINED_PRESSURE check runs, with fixed rules for explanations.
 * Not thread-safe: one instance per monitoring session, used from one coroutine.
 */
class AnomalyDetector(
    private val bundle: ModelBundle?,
    private val severity: SeverityClassifier = SeverityClassifier(),
    private val explainer: ExplanationEngine = ExplanationEngine(),
    defaultWindowSeconds: Int = 60,
) {
    val features = FeatureProcessor(bundle?.windowSeconds ?: defaultWindowSeconds)
    private var consecutive = 0

    /** Last computed feature vector (for storage / research export). */
    var lastFeatures: DoubleArray? = null
        private set

    fun process(sample: RawSample, activity: String?): DetectionResult {
        val x = features.next(sample)
        lastFeatures = x
        if (bundle == null) return ruleBased(sample.timestampMs, x)

        val forest = bundle.forActivity(activity)
        val score = forest.score(x)
        val anomalous = forest.isAnomalous(score)
        val outcome = severity.classify(x, score, forest.scoreThreshold, consecutive)
        consecutive = if (anomalous) consecutive + 1 else 0
        val devs = if (outcome.category == AlertCategory.NORMAL) emptyList() else explainer.deviations(x, forest.baseline)
        return DetectionResult(
            timestampMs = sample.timestampMs,
            source = DetectionSource.ISOLATION_FOREST,
            modelName = forest.name,
            modelVersion = bundle.modelVersion,
            score = score,
            threshold = forest.scoreThreshold,
            isAnomalous = anomalous,
            category = outcome.category,
            severity = outcome.severity,
            deviations = devs,
            explanation = explainer.explain(outcome.category, devs, ruleBased = false),
            recommendations = if (outcome.category == AlertCategory.NORMAL) emptyList() else explainer.recommendations(devs, x),
        )
    }

    private fun ruleBased(t: Long, x: DoubleArray): DetectionResult {
        val outcome = severity.classify(x, null, null, 0)
        val devs = if (outcome.category == AlertCategory.NORMAL) emptyList() else explainer.deviations(x, COLD_START_BASELINE)
        return DetectionResult(
            timestampMs = t, source = DetectionSource.RULE_BASED_COLD_START, modelName = null, modelVersion = null,
            score = null, threshold = null, isAnomalous = false, category = outcome.category, severity = outcome.severity,
            deviations = devs, explanation = explainer.explain(outcome.category, devs, ruleBased = true),
            recommendations = if (outcome.category == AlertCategory.NORMAL) emptyList() else explainer.recommendations(devs, x),
        )
    }

    companion object {
        /** Generic reference values used only for cold-start wording (NOT learned from this device). */
        private val COLD_START_BASELINE: Baseline = run {
            val med = DoubleArray(FeatureSchema.SIZE).also {
                it[FeatureSchema.MEM_AVAIL_RATIO] = 0.40; it[FeatureSchema.MEM_MARGIN_RATIO] = 0.30
                it[FeatureSchema.THERMAL_STATUS] = 0.0; it[FeatureSchema.BATTERY_TEMP_C] = 32.0
                it[FeatureSchema.STORAGE_FREE_RATIO] = 0.40; it[FeatureSchema.THERMAL_HEADROOM] = 0.4
            }
            val spread = DoubleArray(FeatureSchema.SIZE) { 0.05 }
            Baseline(DoubleArray(FeatureSchema.SIZE) { med[it] - spread[it] }, med,
                DoubleArray(FeatureSchema.SIZE) { med[it] + spread[it] })
        }
    }
}
