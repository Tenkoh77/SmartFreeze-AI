package com.buse.smartfreeze.domain

import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.DetectionSource
import com.buse.smartfreeze.domain.model.RawSample
import com.buse.smartfreeze.domain.model.Severity
import com.buse.smartfreeze.ml.Baseline
import com.buse.smartfreeze.ml.FeatureProcessor
import com.buse.smartfreeze.ml.FeatureSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainLogicTest {

    private val GiB = 1024L * 1024 * 1024

    private fun sample(t: Long, availFrac: Double, thermal: Int? = 0, low: Boolean = false) = RawSample(
        timestampMs = t, intervalS = 5, memTotalBytes = 8 * GiB, memAvailBytes = (availFrac * 8 * GiB).toLong(),
        memThresholdBytes = GiB / 2, lowMemory = low, batteryLevelPct = 60, isCharging = false,
        thermalStatus = thermal, thermalHeadroom = null, batteryTempC = 31.0,
        storageTotalBytes = 100, storageAvailBytes = 40,
    )

    private fun features(vararg s: RawSample): DoubleArray {
        val fp = FeatureProcessor(60)
        var x = DoubleArray(0)
        s.forEach { x = fp.next(it) }
        return x
    }

    @Test
    fun severityUsesMoreThanScore() {
        val sc = SeverityClassifier()
        val calm = features(sample(0, 0.5))
        assertEquals(AlertCategory.NORMAL, sc.classify(calm, 0.55, 0.6, 0).category)
        // just over threshold, no other evidence -> LOW
        assertEquals(Severity.LOW, sc.classify(calm, 0.61, 0.6, 0).severity)
        // strong margin + persistence -> MEDIUM
        assertEquals(Severity.MEDIUM, sc.classify(calm, 0.70, 0.6, 5).severity)
        // + low memory and sustained pressure -> HIGH
        val stressed = features(*(0 until 12).map { sample(it * 5000L, 0.05, thermal = 3, low = true) }.toTypedArray())
        val o = sc.classify(stressed, 0.70, 0.6, 5)
        assertEquals(AlertCategory.UNUSUAL_CONDITION, o.category)
        assertEquals(Severity.HIGH, o.severity)
    }

    @Test
    fun sustainedPressureIsRaisedWithoutModel() {
        val stressed = features(*(0 until 12).map { sample(it * 5000L, 0.05, low = true) }.toTypedArray())
        val o = SeverityClassifier().classify(stressed, null, null, 0)
        assertEquals(AlertCategory.SUSTAINED_PRESSURE, o.category)
    }

    @Test
    fun alertPolicySuppressesRepeatsButAllowsEscalation() {
        val p = AlertPolicy(cooldownMs = 600_000, minSeverity = Severity.LOW)
        val cat = AlertCategory.UNUSUAL_CONDITION
        assertEquals(AlertPolicy.Decision.ALERT, p.decide(cat, Severity.MEDIUM, 0))
        assertEquals(AlertPolicy.Decision.SUPPRESSED_COOLDOWN, p.decide(cat, Severity.MEDIUM, 60_000))
        assertEquals(AlertPolicy.Decision.SUPPRESSED_COOLDOWN, p.decide(cat, Severity.LOW, 60_000))
        assertEquals(AlertPolicy.Decision.ALERT, p.decide(cat, Severity.HIGH, 120_000))
        assertEquals(AlertPolicy.Decision.ALERT, p.decide(cat, Severity.MEDIUM, 120_000 + 600_000))
        assertEquals(AlertPolicy.Decision.NOT_ALERTABLE, p.decide(AlertCategory.NORMAL, null, 0))
        p.minSeverity = Severity.HIGH
        assertEquals(AlertPolicy.Decision.BELOW_PREFERENCE, p.decide(AlertCategory.SUSTAINED_PRESSURE, Severity.MEDIUM, 0))
    }

    @Test
    fun explanationIsDescriptiveAndHonest() {
        val med = DoubleArray(FeatureSchema.SIZE).also { it[FeatureSchema.MEM_AVAIL_RATIO] = 0.45 }
        val b = Baseline(DoubleArray(FeatureSchema.SIZE) { med[it] - 0.02 }, med, DoubleArray(FeatureSchema.SIZE) { med[it] + 0.02 })
        val x = DoubleArray(FeatureSchema.SIZE).also { it[FeatureSchema.MEM_AVAIL_RATIO] = 0.10 }
        val e = ExplanationEngine()
        val devs = e.deviations(x, b)
        assertEquals(FeatureSchema.MEM_AVAIL_RATIO, devs.first().featureIndex)
        assertTrue(devs.first().robustZ < 0)
        val text = e.explain(AlertCategory.UNUSUAL_CONDITION, devs, ruleBased = false)
        assertTrue(text.contains("unusually low level of available memory"))
        assertTrue(text.contains("does not by itself mean the phone will freeze"))
        assertTrue(e.recommendations(devs, x).contains("Close applications you no longer need."))
        assertTrue(e.explain(AlertCategory.SUSTAINED_PRESSURE, devs, ruleBased = true).startsWith("A rule-based check (not machine learning)"))
    }

    @Test
    fun coldStartProducesNoMlResult() {
        val d = AnomalyDetector(bundle = null)
        val r = d.process(sample(0, 0.5), null)
        assertEquals(DetectionSource.RULE_BASED_COLD_START, r.source)
        assertNull(r.score)
        assertEquals(AlertCategory.NORMAL, r.category)
    }

    @Test(expected = IllegalArgumentException::class)
    fun featureProcessorRejectsOutOfOrderSamples() {
        val fp = FeatureProcessor(60)
        fp.next(sample(10_000, 0.5))
        fp.next(sample(5_000, 0.5))
    }
}
