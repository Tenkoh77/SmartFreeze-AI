package com.buse.smartfreeze.domain.model

/** Where a result came from. The UI must label rule-based results as NOT machine learning. */
enum class DetectionSource { ISOLATION_FOREST, RULE_BASED_COLD_START }

enum class AlertCategory(val label: String, val isPrediction: Boolean) {
    NORMAL("Normal operation", false),
    UNUSUAL_CONDITION("Unusual performance condition", false),
    SUSTAINED_PRESSURE("Sustained resource pressure", false),
    /** Only produced when a validated slowdown-risk model is installed (none ships by default). */
    ELEVATED_SLOWDOWN_RISK("Elevated slowdown risk (experimental prediction)", true),
}

enum class Severity { LOW, MEDIUM, HIGH }

/** Descriptive deviation of one feature from the user's baseline (not a causal attribution). */
data class FeatureDeviation(
    val featureIndex: Int,
    val featureName: String,
    val value: Double,
    val baselineMedian: Double,
    val robustZ: Double,
)

data class DetectionResult(
    val timestampMs: Long,
    val source: DetectionSource,
    val modelName: String?,          // "general" or "activity:<label>"; null for rule-based
    val modelVersion: String?,
    val score: Double?,              // null for rule-based
    val threshold: Double?,
    val isAnomalous: Boolean,
    val category: AlertCategory,
    val severity: Severity?,         // null when category == NORMAL
    val deviations: List<FeatureDeviation>,
    val explanation: String,
    val recommendations: List<String>,
)
