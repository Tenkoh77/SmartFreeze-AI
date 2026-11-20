package com.buse.smartfreeze.ml

import com.buse.smartfreeze.ml.json.JsonException
import com.buse.smartfreeze.ml.json.MiniJson
import com.buse.smartfreeze.ml.json.asDouble
import com.buse.smartfreeze.ml.json.asInt
import com.buse.smartfreeze.ml.json.asList
import com.buse.smartfreeze.ml.json.asObj
import com.buse.smartfreeze.ml.json.asString
import kotlin.math.ln
import kotlin.math.pow

/**
 * On-device Isolation Forest inference (Liu, Ting & Zhou, 2008), reading the forest
 * exported by machine-learning/src/smartfreeze_ml/export/model_export.py.
 *
 * Score orientation (identical to Python):
 *   anomalyScore = 2^(-E[h(x)] / c(maxSamples))  ==  -IsolationForest.score_samples(x)
 *   anomalous  <=>  anomalyScore >= scoreThreshold
 * h(x) = number of edges from root to the leaf reached by x, plus c(n_leaf), the
 * average path length of the n_leaf training samples that were not separated further.
 *
 * The score expresses how easily an observation is isolated from the learned baseline.
 * It is NOT a probability that the phone will freeze.
 */
class IsolationTree(
    val left: IntArray,
    val right: IntArray,
    val feature: IntArray,
    val threshold: DoubleArray,
    val nodeSamples: IntArray,
) {
    init {
        val n = left.size
        require(n > 0 && right.size == n && feature.size == n && threshold.size == n && nodeSamples.size == n) {
            "tree arrays must be non-empty and equally sized"
        }
        for (i in 0 until n) {
            val leaf = left[i] == -1
            require(leaf == (right[i] == -1)) { "node $i has exactly one child" }
            if (!leaf) {
                require(left[i] in (i + 1) until n && right[i] in (i + 1) until n) { "node $i child index invalid" }
                require(feature[i] in 0 until FeatureSchema.SIZE) { "node $i feature index invalid" }
                require(!threshold[i].isNaN()) { "node $i threshold is NaN" }
            } else {
                require(nodeSamples[i] >= 1) { "leaf $i sample count invalid" }
            }
        }
    }

    /** Path length h(x) for an already-imputed feature vector. */
    fun pathLength(x: DoubleArray): Double {
        var node = 0
        var depth = 0
        while (left[node] != -1) {
            // scikit-learn trees cast inputs to float32 before comparing with the float64 threshold.
            val xv = x[feature[node]].toFloat().toDouble()
            node = if (xv <= threshold[node]) left[node] else right[node]
            depth++
        }
        return depth + averagePathLength(nodeSamples[node])
    }

    companion object {
        private const val EULER_GAMMA = 0.5772156649015329

        /** c(n) as defined in the Isolation Forest paper and scikit-learn. */
        fun averagePathLength(n: Int): Double = when {
            n <= 1 -> 0.0
            n == 2 -> 1.0
            else -> 2.0 * (ln(n - 1.0) + EULER_GAMMA) - 2.0 * (n - 1.0) / n
        }
    }
}

data class Baseline(val q25: DoubleArray, val median: DoubleArray, val q75: DoubleArray)

class IsolationForest(
    val name: String,
    val trees: List<IsolationTree>,
    val maxSamples: Int,
    val scoreThreshold: Double,
    val thresholdMethod: String,
    val nTrain: Int,
    val imputation: DoubleArray,
    val baseline: Baseline,
) {
    private val denominator = trees.size * IsolationTree.averagePathLength(maxSamples)

    init {
        require(trees.isNotEmpty()) { "forest has no trees" }
        require(maxSamples >= 2) { "maxSamples must be >= 2" }
        require(imputation.size == FeatureSchema.SIZE && imputation.none { it.isNaN() }) { "imputation vector invalid" }
        require(scoreThreshold > 0.0 && scoreThreshold < 1.0) { "scoreThreshold must be in (0, 1)" }
    }

    fun impute(x: DoubleArray): DoubleArray {
        require(x.size == FeatureSchema.SIZE) { "expected ${FeatureSchema.SIZE} features, got ${x.size}" }
        return DoubleArray(x.size) { i -> if (x[i].isFinite()) x[i] else imputation[i] }
    }

    /** Anomaly score in (0, 1]; higher = more anomalous. Missing (NaN) features are imputed. */
    fun score(x: DoubleArray): Double {
        val xi = impute(x)
        var total = 0.0
        for (t in trees) total += t.pathLength(xi)
        return 2.0.pow(-total / denominator)
    }

    fun isAnomalous(score: Double): Boolean = score >= scoreThreshold

    /** Same trees, imputation and baseline with a different decision threshold (used by the trainer). */
    fun withThreshold(threshold: Double, method: String): IsolationForest =
        IsolationForest(name, trees, maxSamples, threshold, method, nTrain, imputation, baseline)
}

/** Parsed model bundle: a general forest plus optional activity-specific forests. */
class ModelBundle(
    val modelVersion: String,
    val createdUtc: String,
    val syntheticTrainingData: Boolean,
    val windowSeconds: Int,
    val general: IsolationForest,
    val activityModels: Map<String, IsolationForest>,
    /** "offline" (trained in Python, shipped in the APK), "on_device" or "on_device_demo". */
    val origin: String = ORIGIN_OFFLINE,
    /** Free-form training information (sample counts, training time, ...). Values are String/Number/Boolean. */
    val metadata: Map<String, Any?> = emptyMap(),
) {
    val isOnDevice: Boolean get() = origin == ORIGIN_ON_DEVICE || origin == ORIGIN_ON_DEVICE_DEMO
    val isDemoQuality: Boolean get() = origin == ORIGIN_ON_DEVICE_DEMO

    /** Approach A with fallback: use the activity model when one exists, otherwise the general model. */
    fun forActivity(activity: String?): IsolationForest =
        activity?.let { activityModels[it] } ?: general

    companion object {
        const val FORMAT = "smartfreeze-iforest"
        const val FORMAT_VERSION = 1
        const val ORIGIN_OFFLINE = "offline"
        const val ORIGIN_ON_DEVICE = "on_device"
        const val ORIGIN_ON_DEVICE_DEMO = "on_device_demo"
        private const val MAX_BYTES = 20 * 1024 * 1024

        /** Parses and validates a bundle. Throws [JsonException] / [IllegalArgumentException] if malformed. */
        fun parse(json: String): ModelBundle {
            require(json.length <= MAX_BYTES) { "model file too large" }
            val root = MiniJson.parse(json).asObj("root")
            if (root["format"].asString("format") != FORMAT) throw JsonException("not a SmartFreeze model")
            val fv = root["format_version"].asInt("format_version")
            if (fv != FORMAT_VERSION) throw JsonException("unsupported format_version $fv")
            val names = root["feature_names"].asList("feature_names").map { it.asString("feature name") }
            if (names != FeatureSchema.NAMES) throw JsonException("feature layout does not match this app version")
            val window = root["window_seconds"].asInt("window_seconds")
            require(window in 5..3600) { "window_seconds out of range" }
            val models = root["models"].asObj("models")
            val general = parseForest("general", models["general"].asObj("models.general"))
            val activity = models.filterKeys { it.startsWith("activity:") }
                .map { (k, v) -> k.removePrefix("activity:") to parseForest(k, v.asObj(k)) }.toMap()
            return ModelBundle(
                modelVersion = root["model_version"].asString("model_version"),
                createdUtc = root["created_utc"].asString("created_utc"),
                syntheticTrainingData = root["synthetic_training_data"] as? Boolean
                    ?: throw JsonException("synthetic_training_data: expected boolean"),
                windowSeconds = window,
                general = general,
                activityModels = activity,
                origin = (root["origin"] as? String) ?: ORIGIN_OFFLINE,
                metadata = (root["metadata"] as? Map<*, *>)?.entries
                    ?.associate { (k, v) -> k.toString() to v } ?: emptyMap(),
            )
        }

        private fun doubles(v: Any?, what: String, size: Int? = null): DoubleArray {
            val l = v.asList(what)
            if (size != null && l.size != size) throw JsonException("$what: expected $size values")
            return DoubleArray(l.size) { l[it].asDouble(what) }
        }

        private fun ints(v: Any?, what: String): IntArray {
            val l = v.asList(what)
            return IntArray(l.size) { l[it].asInt(what) }
        }

        private fun parseForest(name: String, m: Map<String, Any?>): IsolationForest {
            val trees = m["trees"].asList("$name.trees").mapIndexed { i, t ->
                val o = t.asObj("$name.trees[$i]")
                IsolationTree(ints(o["left"], "left"), ints(o["right"], "right"), ints(o["feature"], "feature"),
                    doubles(o["threshold"], "threshold"), ints(o["n"], "n"))
            }
            val b = m["baseline"].asObj("$name.baseline")
            return IsolationForest(
                name = name,
                trees = trees,
                maxSamples = m["max_samples"].asInt("max_samples"),
                scoreThreshold = m["score_threshold"].asDouble("score_threshold"),
                thresholdMethod = m["threshold_method"].asString("threshold_method"),
                nTrain = m["n_train"].asInt("n_train"),
                imputation = doubles(m["imputation"], "imputation", FeatureSchema.SIZE),
                baseline = Baseline(
                    doubles(b["q25"], "q25", FeatureSchema.SIZE),
                    doubles(b["median"], "median", FeatureSchema.SIZE),
                    doubles(b["q75"], "q75", FeatureSchema.SIZE),
                ),
            )
        }
    }
}
