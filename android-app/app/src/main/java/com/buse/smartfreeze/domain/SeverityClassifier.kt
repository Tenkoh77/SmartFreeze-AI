package com.buse.smartfreeze.domain

import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.Severity
import com.buse.smartfreeze.ml.FeatureSchema

/**
 * Turns one observation into (category, severity) using MORE than the anomaly score:
 * margin above the threshold, persistence (recent consecutive anomalies), sustained
 * resource pressure in the window, and hard Android indicators (low-memory flag,
 * SEVERE or worse thermal status).
 *
 *   points = [score >= threshold + 0.05] + [consecutive >= 3] + [pressure_fraction >= 0.5]
 *            + [lowMemory or thermal >= SEVERE]
 *   LOW: 0-1 points, MEDIUM: 2, HIGH: 3-4
 *
 * SUSTAINED_PRESSURE is a rule-based category (pressure in >= 80% of the window) and can
 * be raised even without a model, for example during cold start.
 */
class SeverityClassifier(
    private val strongMargin: Double = 0.05,
    private val persistentCount: Int = 3,
    private val pressureForPoint: Double = 0.5,
    private val sustainedPressure: Double = 0.8,
) {
    data class Outcome(val category: AlertCategory, val severity: Severity?)

    /**
     * @param score anomaly score, or null when no model is available (cold start)
     * @param consecutiveAnomalies number of immediately preceding anomalous observations in this session
     */
    fun classify(x: DoubleArray, score: Double?, threshold: Double?, consecutiveAnomalies: Int): Outcome {
        val anomalous = score != null && threshold != null && score >= threshold
        val pressure = x[FeatureSchema.PRESSURE_FRACTION_W]
        val thermal = x[FeatureSchema.THERMAL_STATUS]
        val hardSignal = x[FeatureSchema.LOW_MEMORY] == 1.0 ||
            (!thermal.isNaN() && thermal >= FeatureSchema.PRESSURE_THERMAL)
        val sustained = !pressure.isNaN() && pressure >= sustainedPressure

        val category = when {
            anomalous -> AlertCategory.UNUSUAL_CONDITION
            sustained -> AlertCategory.SUSTAINED_PRESSURE
            else -> return Outcome(AlertCategory.NORMAL, null)
        }
        var points = 0
        if (anomalous && score!! >= threshold!! + strongMargin) points++
        if (consecutiveAnomalies >= persistentCount) points++
        if (!pressure.isNaN() && pressure >= pressureForPoint) points++
        if (hardSignal) points++
        val severity = when {
            points >= 3 -> Severity.HIGH
            points == 2 -> Severity.MEDIUM
            else -> Severity.LOW
        }
        return Outcome(category, severity)
    }
}
