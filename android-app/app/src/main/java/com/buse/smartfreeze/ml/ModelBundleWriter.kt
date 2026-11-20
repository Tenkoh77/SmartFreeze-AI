package com.buse.smartfreeze.ml

/**
 * Serialises a [ModelBundle] to the same JSON format the Python exporter writes
 * (format "smartfreeze-iforest", version 1), so a model trained on the phone is stored and
 * re-loaded through exactly the same validated parser ([ModelBundle.parse]) as the offline model.
 */
object ModelBundleWriter {

    fun toJson(b: ModelBundle): String {
        val sb = StringBuilder(256 * 1024)
        sb.append('{')
        field(sb, "format", ModelBundle.FORMAT); sb.append(',')
        sb.append("\"format_version\":").append(ModelBundle.FORMAT_VERSION).append(',')
        field(sb, "model_version", b.modelVersion); sb.append(',')
        field(sb, "created_utc", b.createdUtc); sb.append(',')
        sb.append("\"synthetic_training_data\":").append(b.syntheticTrainingData).append(',')
        sb.append("\"feature_schema_version\":").append(FeatureSchema.VERSION).append(',')
        sb.append("\"feature_names\":[").append(FeatureSchema.NAMES.joinToString(",") { quote(it) }).append("],")
        sb.append("\"window_seconds\":").append(b.windowSeconds).append(',')
        field(sb, "score_orientation", "anomaly_score = -score_samples; anomalous if anomaly_score >= score_threshold"); sb.append(',')
        field(sb, "origin", b.origin); sb.append(',')
        sb.append("\"metadata\":{")
        b.metadata.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(quote(k)).append(':').append(scalar(v))
        }
        sb.append("},\"models\":{")
        val all = listOf("general" to b.general) + b.activityModels.entries.sortedBy { it.key }.map { "activity:${it.key}" to it.value }
        all.forEachIndexed { i, (name, f) ->
            if (i > 0) sb.append(',')
            sb.append(quote(name)).append(':')
            forest(sb, f)
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun forest(sb: StringBuilder, f: IsolationForest) {
        sb.append("{\"n_estimators\":").append(f.trees.size)
        sb.append(",\"max_samples\":").append(f.maxSamples)
        sb.append(",\"score_threshold\":").append(num(f.scoreThreshold))
        sb.append(",\"threshold_method\":").append(quote(f.thresholdMethod))
        sb.append(",\"n_train\":").append(f.nTrain)
        sb.append(",\"imputation\":").append(arr(f.imputation))
        sb.append(",\"baseline\":{\"q25\":").append(arr(f.baseline.q25))
            .append(",\"median\":").append(arr(f.baseline.median))
            .append(",\"q75\":").append(arr(f.baseline.q75)).append('}')
        sb.append(",\"trees\":[")
        f.trees.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"left\":").append(t.left.joinToString(",", "[", "]"))
            sb.append(",\"right\":").append(t.right.joinToString(",", "[", "]"))
            sb.append(",\"feature\":").append(t.feature.joinToString(",", "[", "]"))
            sb.append(",\"threshold\":").append(arr(t.threshold))
            sb.append(",\"n\":").append(t.nodeSamples.joinToString(",", "[", "]")).append('}')
        }
        sb.append("]}")
    }

    private fun arr(a: DoubleArray) = a.joinToString(",", "[", "]") { num(it) }

    private fun num(v: Double): String {
        require(v.isFinite()) { "JSON cannot represent $v" }
        return v.toString()          // e.g. 0.123, 1.0E-5: both valid JSON numbers, exact round trip
    }

    private fun scalar(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Number -> num(v.toDouble())
        else -> quote(v.toString())
    }

    private fun field(sb: StringBuilder, k: String, v: String) { sb.append(quote(k)).append(':').append(quote(v)) }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
