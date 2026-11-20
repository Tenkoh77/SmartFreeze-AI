package com.buse.smartfreeze.ml

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * ON-DEVICE Isolation Forest training (Liu, Ting & Zhou, 2008), so every phone learns its OWN
 * normal baseline without a laptop or CSV export.
 *
 * The algorithm follows scikit-learn's IsolationForest (the reference used in the Python pipeline):
 *  - each tree is grown on `maxSamples` rows drawn WITHOUT replacement (sklearn: min(256, n));
 *  - at every node a feature is chosen at random among those that are not constant in the node,
 *    and the split threshold is drawn uniformly between that feature's min and max in the node;
 *  - a sample goes LEFT when x <= threshold;
 *  - growth stops at depth ceil(log2(maxSamples)), at a single sample, or when all features are constant;
 *  - inputs are rounded to float32 first, exactly as scikit-learn trees do.
 * Scoring reuses [IsolationForest.score], so the score orientation and c(n) correction are identical
 * to the offline model. Because the random number generators differ, Kotlin and scikit-learn grow
 * DIFFERENT random trees; equivalence is checked statistically (see IsolationForestTrainerTest).
 *
 * Decision threshold: chosen on a chronologically later VALIDATION part of the user's own data, as
 * the (1 - targetFpr) quantile of validation scores (default: about 1 % of normal observations flagged),
 * the same rule as the Python pipeline's default.
 *
 * Pure Kotlin (no Android imports): unit-tested on the JVM.
 */
class IsolationForestTrainer(
    private val nEstimators: Int = 100,
    private val maxSamples: Int = 256,
    private val targetFpr: Double = 0.01,
    private val seed: Long = 42L,
    val minTrainRows: Int = 120,
    val minValidationRows: Int = 20,
) {
    init {
        require(nEstimators in 1..1000) { "nEstimators out of range" }
        require(maxSamples >= 2) { "maxSamples must be >= 2" }
        require(targetFpr > 0.0 && targetFpr < 0.5) { "targetFpr out of range" }
    }

    /** One feature vector with the context needed for a leakage-aware split. */
    class Row(val sessionId: Long, val timestampMs: Long, val activity: String?, val x: DoubleArray) {
        init { require(x.size == FeatureSchema.SIZE) { "expected ${FeatureSchema.SIZE} features" } }
    }

    class TrainedModel(
        val forest: IsolationForest,
        val nTrain: Int,
        val nValidation: Int,
        val splitMethod: String,
        val fitMillis: Long,
    )

    class NotEnoughDataException(message: String) : IllegalStateException(message)

    // ------------------------------------------------------------------------------------------
    // Chronological split
    // ------------------------------------------------------------------------------------------

    /**
     * Returns (train, validation). With >= [minSessions] sessions, whole sessions go to one side
     * (the latest ~20 % of sessions are validation), so overlapping windows of a session never
     * appear on both sides. Otherwise a single time cut is used, and rows within [gapMs] after the
     * cut are discarded, so no validation window contains training samples.
     */
    fun chronologicalSplit(
        rows: List<Row>, validationFraction: Double = 0.2, minSessions: Int = 5, gapMs: Long = 60_000L,
    ): Triple<List<Row>, List<Row>, String> {
        require(rows.isNotEmpty()) { "no rows" }
        val starts = rows.groupBy { it.sessionId }.mapValues { (_, r) -> r.minOf { it.timestampMs } }
        if (starts.size >= minSessions) {
            val ordered = starts.entries.sortedBy { it.value }.map { it.key }
            val nVal = max(1, (ordered.size * validationFraction).roundToInt())
            val valSessions = ordered.takeLast(nVal).toSet()
            val train = rows.filter { it.sessionId !in valSessions }
            val validation = rows.filter { it.sessionId in valSessions }
            if (validation.size >= minValidationRows && train.size >= minTrainRows) {
                return Triple(train, validation, "by_session")
            }
        }
        val sorted = rows.sortedBy { it.timestampMs }
        val t0 = sorted.first().timestampMs
        val t1 = sorted.last().timestampMs
        val cut = t0 + ((t1 - t0) * (1 - validationFraction)).toLong()
        val train = sorted.filter { it.timestampMs <= cut }
        val validation = sorted.filter { it.timestampMs > cut + gapMs }
        return Triple(train, validation, "by_time_with_gap")
    }

    // ------------------------------------------------------------------------------------------
    // Full training: split -> fit -> threshold
    // ------------------------------------------------------------------------------------------

    fun train(rows: List<Row>, name: String = "general"): TrainedModel {
        val (train, validation, method) = chronologicalSplit(rows)
        if (train.size < minTrainRows) throw NotEnoughDataException("only ${train.size} training rows (need $minTrainRows)")
        if (validation.size < minValidationRows) throw NotEnoughDataException("only ${validation.size} validation rows (need $minValidationRows)")
        val started = System.nanoTime()
        val provisional = fit(train.map { it.x }, name)
        val valScores = validation.map { provisional.score(it.x) }.toDoubleArray()
        val threshold = quantile(valScores, 1.0 - targetFpr).coerceIn(1e-6, 1.0 - 1e-9)
        val forest = provisional.withThreshold(threshold, "on_device_fpr(target_fpr=$targetFpr)")
        val ms = (System.nanoTime() - started) / 1_000_000
        return TrainedModel(forest, train.size, validation.size, method, ms)
    }

    // ------------------------------------------------------------------------------------------
    // Forest fitting (no threshold selection)
    // ------------------------------------------------------------------------------------------

    /** Fits trees, imputation medians and baseline quartiles. The returned threshold is a placeholder (0.5). */
    fun fit(xs: List<DoubleArray>, name: String): IsolationForest {
        require(xs.size >= 2) { "need at least 2 rows" }
        val medians = DoubleArray(FeatureSchema.SIZE) { j ->
            val v = xs.map { it[j] }.filter { it.isFinite() }.toDoubleArray()
            if (v.isEmpty()) 0.0 else quantile(v, 0.5)
        }
        // Impute, then round to float32 as scikit-learn trees do.
        val data = Array(xs.size) { i ->
            DoubleArray(FeatureSchema.SIZE) { j ->
                val v = xs[i][j]
                (if (v.isFinite()) v else medians[j]).toFloat().toDouble()
            }
        }
        val used = min(maxSamples, data.size)
        val depthLimit = ceil(ln(max(used, 2).toDouble()) / ln(2.0)).toInt()
        val rng = Random(seed)
        val trees = List(nEstimators) {
            val idx = sampleWithoutReplacement(data.size, used, rng)
            TreeBuilder(data, depthLimit, rng).build(idx)
        }
        val baseline = Baseline(
            DoubleArray(FeatureSchema.SIZE) { j -> columnQuantile(xs, j, 0.25) },
            DoubleArray(FeatureSchema.SIZE) { j -> columnQuantile(xs, j, 0.50) },
            DoubleArray(FeatureSchema.SIZE) { j -> columnQuantile(xs, j, 0.75) },
        )
        return IsolationForest(name, trees, used, 0.5, "provisional", xs.size, medians, baseline)
    }

    private class TreeBuilder(private val data: Array<DoubleArray>, private val depthLimit: Int, private val rng: Random) {
        private val left = ArrayList<Int>()
        private val right = ArrayList<Int>()
        private val feature = ArrayList<Int>()
        private val threshold = ArrayList<Double>()
        private val n = ArrayList<Int>()

        fun build(rows: IntArray): IsolationTree {
            grow(rows, 0)
            return IsolationTree(left.toIntArray(), right.toIntArray(), feature.toIntArray(),
                threshold.toDoubleArray(), n.toIntArray())
        }

        /** Pre-order growth: a node's children always have larger indices than the node. */
        private fun grow(rows: IntArray, depth: Int): Int {
            val id = left.size
            left.add(-1); right.add(-1); feature.add(-1); threshold.add(0.0); n.add(rows.size)
            if (depth >= depthLimit || rows.size <= 1) return id

            // Random order over features; take the first one that is not constant in this node.
            val order = (0 until FeatureSchema.SIZE).shuffled(rng)
            for (f in order) {
                var lo = Double.POSITIVE_INFINITY
                var hi = Double.NEGATIVE_INFINITY
                for (r in rows) { val v = data[r][f]; if (v < lo) lo = v; if (v > hi) hi = v }
                if (hi <= lo) continue
                var t = lo + rng.nextDouble() * (hi - lo)
                if (t >= hi) t = lo                    // guard against rounding: keep both sides non-empty
                val l = rows.filter { data[it][f] <= t }.toIntArray()
                val rr = rows.filter { data[it][f] > t }.toIntArray()
                if (l.isEmpty() || rr.isEmpty()) continue
                feature[id] = f
                threshold[id] = t
                left[id] = grow(l, depth + 1)
                right[id] = grow(rr, depth + 1)
                return id
            }
            return id   // every feature constant: leaf
        }
    }

    companion object {
        /** numpy-style linear-interpolation quantile ("linear" method, numpy's default). */
        fun quantile(values: DoubleArray, q: Double): Double {
            require(values.isNotEmpty()) { "empty" }
            val s = values.sortedArray()
            val pos = q.coerceIn(0.0, 1.0) * (s.size - 1)
            val lo = floor(pos).toInt()
            val hi = min(lo + 1, s.size - 1)
            return s[lo] + (s[hi] - s[lo]) * (pos - lo)
        }

        private fun columnQuantile(xs: List<DoubleArray>, j: Int, q: Double): Double {
            val v = xs.map { it[j] }.filter { it.isFinite() }.toDoubleArray()
            return if (v.isEmpty()) 0.0 else quantile(v, q)
        }

        /** Partial Fisher-Yates shuffle: k distinct indices from 0 until n. */
        fun sampleWithoutReplacement(n: Int, k: Int, rng: Random): IntArray {
            val a = IntArray(n) { it }
            for (i in 0 until k) {
                val j = i + rng.nextInt(n - i)
                val tmp = a[i]; a[i] = a[j]; a[j] = tmp
            }
            return a.copyOf(k)
        }
    }
}
