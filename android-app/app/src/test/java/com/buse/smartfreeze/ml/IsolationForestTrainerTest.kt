package com.buse.smartfreeze.ml

import com.buse.smartfreeze.domain.BaselineStats
import com.buse.smartfreeze.domain.TrainingPolicy
import com.buse.smartfreeze.domain.model.RawSample
import com.buse.smartfreeze.ml.json.MiniJson
import com.buse.smartfreeze.ml.json.asDouble
import com.buse.smartfreeze.ml.json.asDoubleOrNaN
import com.buse.smartfreeze.ml.json.asList
import com.buse.smartfreeze.ml.json.asObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

/** Tests for ON-DEVICE training (IsolationForestTrainer, PersonalModelBuilder, ModelBundleWriter, TrainingPolicy). */
class IsolationForestTrainerTest {

    // ---------------------------------------------------------------- helpers

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "missing test resource $path" }.readText()

    private fun matrix(v: Any?): List<DoubleArray> =
        v.asList("matrix").map { r -> r.asList("row").map { it.asDoubleOrNaN() }.toDoubleArray() }

    /** ROC-AUC via the Mann-Whitney statistic (ties count 1/2). */
    private fun rocAuc(scores: DoubleArray, labels: IntArray): Double {
        val pos = scores.filterIndexed { i, _ -> labels[i] == 1 }
        val neg = scores.filterIndexed { i, _ -> labels[i] == 0 }
        var wins = 0.0
        for (p in pos) for (n in neg) wins += if (p > n) 1.0 else if (p == n) 0.5 else 0.0
        return wins / (pos.size.toDouble() * neg.size)
    }

    private fun ranks(v: DoubleArray): DoubleArray {
        val order = v.indices.sortedBy { v[it] }
        val r = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
            val avg = (i + j) / 2.0 + 1
            for (k in i..j) r[order[k]] = avg
            i = j + 1
        }
        return r
    }

    private fun spearman(a: DoubleArray, b: DoubleArray): Double {
        val ra = ranks(a); val rb = ranks(b)
        val ma = ra.average(); val mb = rb.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in ra.indices) { sab += (ra[i] - ma) * (rb[i] - mb); saa += (ra[i] - ma) * (ra[i] - ma); sbb += (rb[i] - mb) * (rb[i] - mb) }
        return sab / Math.sqrt(saa * sbb)
    }

    private fun depthOf(t: IsolationTree): Int {
        fun d(node: Int): Int = if (t.left[node] == -1) 0 else 1 + maxOf(d(t.left[node]), d(t.right[node]))
        return d(0)
    }

    private val GiB = 1024L * 1024 * 1024

    /** Simulated normal phone data: [sessions] sessions of [perSession] samples at 10 s. */
    private fun simulatedSamples(sessions: Int, perSession: Int, seed: Int = 1, activity: (Int) -> String? = { null }):
        List<PersonalModelBuilder.LabelledSample> {
        val rng = Random(seed)
        val out = ArrayList<PersonalModelBuilder.LabelledSample>()
        var t = 1_790_000_000_000L
        for (s in 0 until sessions) {
            var ratio = 0.42
            for (i in 0 until perSession) {
                ratio = (0.85 * ratio + 0.15 * 0.42 + (rng.nextDouble() - 0.5) * 0.04).coerceIn(0.05, 0.9)
                out += PersonalModelBuilder.LabelledSample(
                    s.toLong(), activity(s),
                    RawSample(t, 10, 8 * GiB, (ratio * 8 * GiB).toLong(), GiB / 2, false, 60, false,
                        if (rng.nextDouble() < 0.9) 0 else 1, null, 31.0 + rng.nextDouble(), 100, 40),
                )
                t += 10_000 + rng.nextInt(50)
            }
            t += 3_600_000
        }
        return out
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun matchesScikitLearnStatistically() {
        val fx = MiniJson.parse(resource("parity/training_fixture.json")).asObj("fixture")
        val train = matrix(fx["train"])
        val test = matrix(fx["test"])
        val labels = fx["test_labels"].asList("labels").map { (it as Number).toInt() }.toIntArray()
        val sk = fx["sklearn_scores"].asList("scores").map { it.asDouble("s") }.toDoubleArray()
        val skAuc = fx["sklearn_roc_auc"].asDouble("auc")

        val aucs = ArrayList<Double>()
        val rhos = ArrayList<Double>()
        for (seed in 1L..5L) {
            val forest = IsolationForestTrainer(seed = seed).fit(train, "t")
            val kt = test.map { forest.score(it) }.toDoubleArray()
            aucs += rocAuc(kt, labels)
            rhos += spearman(kt, sk)
        }
        val meanAuc = aucs.average()
        println("Trainer vs scikit-learn: sklearn ROC-AUC=%.4f, Kotlin ROC-AUC mean=%.4f (min %.4f, max %.4f), Spearman mean=%.4f (min %.4f)"
            .format(skAuc, meanAuc, aucs.min(), aucs.max(), rhos.average(), rhos.min()))
        assertTrue("Kotlin AUC $meanAuc differs from sklearn $skAuc by more than 0.03", abs(meanAuc - skAuc) <= 0.03)
        assertTrue("score rankings disagree with sklearn (min rho ${rhos.min()})", rhos.min() >= 0.85)
    }

    @Test
    fun deterministicForSameSeed() {
        val rows = simulatedSamples(2, 200).map { it.sample }
        val fp = FeatureProcessor(60)
        val xs = rows.map { fp.next(it) }
        val a = IsolationForestTrainer(seed = 7).fit(xs, "a")
        val b = IsolationForestTrainer(seed = 7).fit(xs, "b")
        val c = IsolationForestTrainer(seed = 8).fit(xs, "c")
        val x = xs[123]
        assertEquals(a.score(x), b.score(x), 0.0)
        assertTrue(a.score(x) != c.score(x))
    }

    @Test
    fun treesRespectSubsampleAndDepthLimit() {
        val xs = List(1000) { i -> DoubleArray(FeatureSchema.SIZE) { j -> ((i * 7919 + j * 104729) % 1000) / 1000.0 } }
        val f = IsolationForestTrainer(nEstimators = 20).fit(xs, "t")
        val limit = ceil(ln(256.0) / ln(2.0)).toInt()
        assertEquals(256, f.maxSamples)
        for (t in f.trees) {
            assertEquals(256, t.nodeSamples[0])
            assertTrue(depthOf(t) <= limit)
            // every internal node's children partition its samples
            for (i in t.left.indices) if (t.left[i] != -1) assertEquals(t.nodeSamples[i], t.nodeSamples[t.left[i]] + t.nodeSamples[t.right[i]])
        }
        // small data: subsample shrinks to n
        assertEquals(50, IsolationForestTrainer(nEstimators = 5).fit(xs.take(50), "s").maxSamples)
    }

    @Test
    fun outlierScoresAboveNormalData() {
        val rng = Random(3)
        val xs = List(600) { DoubleArray(FeatureSchema.SIZE) { 0.5 + (rng.nextDouble() - 0.5) * 0.1 } }
        val f = IsolationForestTrainer().fit(xs, "t")
        val normal = xs.map { f.score(it) }.toDoubleArray()
        // Shifted in every feature. (A point extreme in only 2 of 16 features while sitting exactly at the centre
        // of the others is NOT isolated early, in scikit-learn as well: a known Isolation Forest weakness.)
        val outlier = f.score(DoubleArray(FeatureSchema.SIZE) { 0.9 })
        assertTrue("outlier $outlier vs normal p99 ${IsolationForestTrainer.quantile(normal, 0.99)}",
            outlier > IsolationForestTrainer.quantile(normal, 0.99) && outlier > 0.6)
    }

    @Test
    fun missingFeatureIsImputedAndNeverSplitOn() {
        val rng = Random(5)
        val xs = List(300) { DoubleArray(FeatureSchema.SIZE) { j -> if (j == FeatureSchema.THERMAL_HEADROOM) Double.NaN else rng.nextDouble() } }
        val f = IsolationForestTrainer(nEstimators = 30).fit(xs, "t")
        assertEquals(0.0, f.imputation[FeatureSchema.THERMAL_HEADROOM], 0.0)
        assertTrue(f.trees.all { t -> t.feature.none { it == FeatureSchema.THERMAL_HEADROOM } })
        assertFalse(f.score(xs[0]).isNaN())
    }

    @Test
    fun chronologicalSplitHasNoLeakage() {
        val tr = IsolationForestTrainer()
        // >= 5 sessions: whole sessions per side, validation sessions are the latest ones
        val rows = simulatedSamples(6, 60).let { PersonalModelBuilder().featureRows(it) }
        val (a, b, m) = tr.chronologicalSplit(rows)
        assertEquals("by_session", m)
        assertTrue(a.map { it.sessionId }.toSet().intersect(b.map { it.sessionId }.toSet()).isEmpty())
        assertTrue(a.maxOf { it.timestampMs } < b.minOf { it.timestampMs })
        // one session: time cut with a gap of at least one window
        val one = PersonalModelBuilder().featureRows(simulatedSamples(1, 300))
        val (c, d, m2) = tr.chronologicalSplit(one)
        assertEquals("by_time_with_gap", m2)
        assertTrue(d.minOf { it.timestampMs } - c.maxOf { it.timestampMs } > 60_000)
    }

    @Test
    fun validatedThresholdFlagsAboutOnePercentOfNormalData() {
        val samples = simulatedSamples(8, 400, seed = 11)
        val rows = PersonalModelBuilder().featureRows(samples)
        val model = IsolationForestTrainer().train(rows)
        val flagged = rows.count { model.forest.isAnomalous(model.forest.score(it.x)) }.toDouble() / rows.size
        println("On-device training: ${model.nTrain} train / ${model.nValidation} validation rows, split ${model.splitMethod}, " +
            "fit ${model.fitMillis} ms, threshold %.4f, flagged %.2f%% of normal rows".format(model.forest.scoreThreshold, flagged * 100))
        assertTrue("flagged $flagged of normal data", flagged in 0.0..0.05)
    }

    @Test
    fun builderProducesLoadableBundleThatRoundTripsExactly() {
        val samples = simulatedSamples(6, 250) { s -> if (s % 2 == 0) "idle" else "browsing" }
        val result = PersonalModelBuilder(minActivityRows = 400).build(samples, demo = false, nowMs = 1_790_000_000_000L)
        val b = result.bundle
        assertEquals(ModelBundle.ORIGIN_ON_DEVICE, b.origin)
        assertFalse(b.syntheticTrainingData)
        assertEquals(setOf("idle", "browsing"), b.activityModels.keys)

        val json = ModelBundleWriter.toJson(b)
        val back = ModelBundle.parse(json)                      // same validated parser as the offline model
        assertEquals(b.modelVersion, back.modelVersion)
        assertEquals(ModelBundle.ORIGIN_ON_DEVICE, back.origin)
        assertEquals(1_790_000_000_000L, (back.metadata["trained_at_ms"] as Number).toLong())
        val rows = PersonalModelBuilder().featureRows(samples)
        for (r in rows.take(200)) {
            assertEquals(b.general.score(r.x), back.general.score(r.x), 0.0)
            assertEquals(b.forActivity("idle").score(r.x), back.forActivity("idle").score(r.x), 0.0)
        }
        assertEquals(b.general.scoreThreshold, back.general.scoreThreshold, 0.0)
    }

    @Test
    fun demoBuildIsLabelledAndSkipsActivityModels() {
        val b = PersonalModelBuilder().build(simulatedSamples(1, 200) { "idle" }, demo = true).bundle
        assertEquals(ModelBundle.ORIGIN_ON_DEVICE_DEMO, b.origin)
        assertTrue(b.isDemoQuality)
        assertTrue(b.activityModels.isEmpty())
        assertTrue(b.modelVersion.startsWith("demo-"))
    }

    @Test(expected = IsolationForestTrainer.NotEnoughDataException::class)
    fun refusesToTrainOnTooLittleData() {
        PersonalModelBuilder().build(simulatedSamples(1, 60), demo = true)
    }

    @Test
    fun writerEscapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\n\"", ModelBundleWriter.quote("a\"b\\c\n"))
    }

    @Test
    fun trainingPolicyReadinessAndRetraining() {
        val p = TrainingPolicy()
        val none = BaselineStats(0, 0, 0)
        val partial = BaselineStats(samples = 1000, days = 2, monitoredSeconds = 3 * 3600)
        val full = BaselineStats(samples = 2200, days = 3, monitoredSeconds = 6 * 3600 + 60)
        assertEquals(0.0, p.progress(none), 0.0)
        assertEquals(0.5, p.progress(partial), 1e-9)          // hours 0.5, samples 0.5, days 0.667 -> min
        assertFalse(p.isReady(partial))
        assertTrue(p.isReady(full))
        assertEquals(1.0, p.progress(full), 0.0)
        assertTrue(p.remaining(partial).any { it.contains("more day") })
        assertTrue(p.isDemoReady(BaselineStats(samples = 180, days = 1, monitoredSeconds = 1800)))
        assertFalse(p.isDemoReady(BaselineStats(samples = 100, days = 1, monitoredSeconds = 1000)))
        val day = 86_400_000L
        assertFalse(p.shouldRetrain(trainedAtMs = 0, newSamplesSince = 5000, nowMs = 6 * day))
        assertFalse(p.shouldRetrain(trainedAtMs = 0, newSamplesSince = 100, nowMs = 8 * day))
        assertTrue(p.shouldRetrain(trainedAtMs = 0, newSamplesSince = 600, nowMs = 8 * day))
    }
}
