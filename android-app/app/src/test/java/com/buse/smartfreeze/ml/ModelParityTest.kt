package com.buse.smartfreeze.ml

import com.buse.smartfreeze.domain.model.RawSample
import com.buse.smartfreeze.ml.json.MiniJson
import com.buse.smartfreeze.ml.json.asDouble
import com.buse.smartfreeze.ml.json.asDoubleOrNaN
import com.buse.smartfreeze.ml.json.asList
import com.buse.smartfreeze.ml.json.asObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Python <-> Kotlin parity test.
 *
 * Fixtures are produced by `python -m smartfreeze_ml.pipeline ... --install`:
 *   resources/parity/smartfreeze_model.json   exported forest
 *   resources/parity/parity_fixtures.json     raw samples, Python features, Python scores
 *
 * Tolerance 1e-9: both sides use IEEE-754 doubles, but summation order differs slightly
 * (Python 3.12+ sum() uses compensated summation), giving differences of order 1e-15.
 * Classifications must agree exactly, except for a score lying within the tolerance of the
 * threshold (none expected; such cases are counted and must be zero).
 */
class ModelParityTest {

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "missing test resource $path" }.readText()

    private val bundle by lazy { ModelBundle.parse(resource("parity/smartfreeze_model.json")) }
    private val fixtures by lazy { MiniJson.parse(resource("parity/parity_fixtures.json")).asObj("fixtures") }

    private fun Any?.longOrNull(): Long? = (this as? Number)?.toLong()

    private fun toSample(m: Map<String, Any?>) = RawSample(
        timestampMs = m["timestamp_ms"].longOrNull()!!,
        intervalS = m["interval_s"].longOrNull()!!.toInt(),
        memTotalBytes = m["mem_total_bytes"].longOrNull()!!,
        memAvailBytes = m["mem_avail_bytes"].longOrNull()!!,
        memThresholdBytes = m["mem_threshold_bytes"].longOrNull()!!,
        lowMemory = m["low_memory"].longOrNull() == 1L,
        batteryLevelPct = m["battery_level_pct"].longOrNull()!!.toInt(),
        isCharging = m["is_charging"].longOrNull() == 1L,
        thermalStatus = m["thermal_status"].longOrNull()?.toInt(),
        thermalHeadroom = (m["thermal_headroom"] as? Number)?.toDouble(),
        batteryTempC = (m["battery_temp_c"] as? Number)?.toDouble(),
        storageTotalBytes = m["storage_total_bytes"].longOrNull(),
        storageAvailBytes = m["storage_avail_bytes"].longOrNull(),
    )

    @Test
    fun windowMatchesFixtures() {
        assertEquals((fixtures["window_seconds"] as Number).toInt(), bundle.windowSeconds)
    }

    @Test
    fun featuresMatchPython() {
        val tol = fixtures["feature_tolerance"].asDouble("tol")
        var checked = 0
        for (s in fixtures["sessions"].asList("sessions")) {
            val sess = s.asObj("session")
            val fp = FeatureProcessor(bundle.windowSeconds)
            val samples = sess["samples"].asList("samples")
            val expected = sess["features"].asList("features")
            for ((i, raw) in samples.withIndex()) {
                val got = fp.next(toSample(raw.asObj("sample")))
                val exp = expected[i].asList("row")
                for (j in got.indices) {
                    val e = exp[j].asDoubleOrNaN()
                    if (e.isNaN()) assertTrue("row $i feature ${FeatureSchema.NAMES[j]} should be NaN", got[j].isNaN())
                    else assertEquals("row $i feature ${FeatureSchema.NAMES[j]}", e, got[j], tol)
                    checked++
                }
            }
        }
        assertTrue("no features compared", checked > 0)
    }

    @Test
    fun scoresAndClassificationsMatchPython() {
        val tol = fixtures["score_tolerance"].asDouble("tol")
        var n = 0
        var nearThreshold = 0
        var maxDiff = 0.0
        val forest = bundle.general
        for (s in fixtures["sessions"].asList("sessions")) {
            val sess = s.asObj("session")
            val feats = sess["features"].asList("features")
            val scores = sess["scores"].asList("scores")
            val flags = sess["anomalous"].asList("anomalous")
            for (i in feats.indices) {
                val x = feats[i].asList("row").map { it.asDoubleOrNaN() }.toDoubleArray()
                val score = forest.score(x)
                val expected = scores[i].asDouble("score")
                maxDiff = maxOf(maxDiff, abs(score - expected))
                assertEquals("score row $i", expected, score, tol)
                if (abs(expected - forest.scoreThreshold) < tol) nearThreshold++
                else assertEquals("classification row $i", flags[i] as Boolean, forest.isAnomalous(score))
                n++
            }
        }
        assertTrue("no scores compared", n > 0)
        assertEquals("observations within tolerance of the threshold", 0, nearThreshold)
        println("ModelParityTest: $n scores compared, max |kotlin - python| = $maxDiff")
    }
}
