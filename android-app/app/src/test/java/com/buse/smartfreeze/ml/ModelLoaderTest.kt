package com.buse.smartfreeze.ml

import com.buse.smartfreeze.ml.json.JsonException
import com.buse.smartfreeze.ml.json.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Protection against malformed or incompatible model files. */
class ModelLoaderTest {

    private val names = FeatureSchema.NAMES.joinToString(",") { "\"$it\"" }
    private val zeros = List(FeatureSchema.SIZE) { "0.0" }.joinToString(",")

    private fun bundle(tree: String = """{"left":[1,-1,-1],"right":[2,-1,-1],"feature":[0,-1,-1],"threshold":[0.5,0,0],"n":[4,2,2]}""",
                       featureNames: String = names, threshold: String = "0.6", format: String = "smartfreeze-iforest") = """
        {"format":"$format","format_version":1,"model_version":"t","created_utc":"x","synthetic_training_data":true,
         "feature_names":[$featureNames],"window_seconds":60,
         "models":{"general":{"n_estimators":1,"max_samples":4,"score_threshold":$threshold,"threshold_method":"fpr",
           "n_train":4,"imputation":[$zeros],"baseline":{"q25":[$zeros],"median":[$zeros],"q75":[$zeros]},
           "trees":[$tree]}}}
    """.trimIndent()

    private fun assertRejected(json: String) {
        try {
            ModelBundle.parse(json)
            fail("expected the model to be rejected")
        } catch (e: JsonException) { /* expected */ } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun parsesMinimalValidBundleAndScoresByHand() {
        val b = ModelBundle.parse(bundle())
        assertTrue(b.syntheticTrainingData)
        // x[0] = 0.0 goes left: depth 1 + c(2) = 2. Denominator: 1 tree * c(4).
        val c4 = IsolationTree.averagePathLength(4)
        val expected = Math.pow(2.0, -2.0 / c4)
        assertEquals(expected, b.general.score(DoubleArray(FeatureSchema.SIZE)), 1e-12)
        assertEquals(b.general, b.forActivity("gaming"))   // falls back to the general model
    }

    @Test
    fun nanFeaturesAreImputed() {
        val b = ModelBundle.parse(bundle())
        val x = DoubleArray(FeatureSchema.SIZE) { Double.NaN }
        assertFalse(b.general.score(x).isNaN())
    }

    @Test fun rejectsWrongFormat() = assertRejected(bundle(format = "other"))
    @Test fun rejectsFeatureLayoutMismatch() = assertRejected(bundle(featureNames = "\"a\""))
    @Test fun rejectsThresholdOutOfRange() = assertRejected(bundle(threshold = "1.5"))
    @Test fun rejectsCyclicTree() = assertRejected(bundle(tree = """{"left":[0,-1,-1],"right":[2,-1,-1],"feature":[0,-1,-1],"threshold":[0.5,0,0],"n":[4,2,2]}"""))
    @Test fun rejectsBadFeatureIndex() = assertRejected(bundle(tree = """{"left":[1,-1,-1],"right":[2,-1,-1],"feature":[99,-1,-1],"threshold":[0.5,0,0],"n":[4,2,2]}"""))
    @Test fun rejectsTruncatedJson() = assertRejected(bundle().dropLast(5))
    @Test fun rejectsNonJson() = assertRejected("not json at all")

    @Test
    fun miniJsonParsesEscapesAndNumbers() {
        val v = MiniJson.parse("""{"a":[1,-2.5e3,true,null,"x\nA"]}""") as Map<*, *>
        assertEquals(listOf(1L, -2500.0, true, null, "x\nA"), v["a"])
    }

    @Test(expected = JsonException::class)
    fun miniJsonLimitsDepth() {
        MiniJson.parse("[".repeat(200) + "]".repeat(200))
    }
}
