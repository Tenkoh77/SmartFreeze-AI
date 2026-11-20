package com.buse.smartfreeze

import com.buse.smartfreeze.data.export.ExportSchema
import com.buse.smartfreeze.ml.FeatureSchema
import com.buse.smartfreeze.ml.json.MiniJson
import com.buse.smartfreeze.ml.json.asList
import com.buse.smartfreeze.ml.json.asObj
import com.buse.smartfreeze.ml.json.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the contract between the Android app and the Python pipeline:
 *  - Kotlin FeatureSchema.NAMES == machine-learning/data/schemas/feature_schema.json
 *  - the exported sample CSV header contains every column that schema.py reads.
 */
class SchemaContractTest {

    private fun repoFile(rel: String): File {
        // Gradle runs unit tests from android-app/app; the local verify script runs from the repo root.
        val candidates = listOf(File("../../$rel"), File(rel), File("../$rel"))
        return candidates.firstOrNull { it.exists() } ?: error("cannot find $rel from ${File(".").absolutePath}")
    }

    @Test
    fun kotlinFeatureOrderMatchesSharedSchema() {
        val json = MiniJson.parse(repoFile("machine-learning/data/schemas/feature_schema.json").readText()).asObj("schema")
        val names = json["features"].asList("features").map { it.asObj("f")["name"].asString("name") }
        assertEquals(names, FeatureSchema.NAMES)
    }

    @Test
    fun csvHeaderCoversPythonRawColumns() {
        val py = repoFile("machine-learning/src/smartfreeze_ml/schema.py").readText()
        fun block(name: String) = Regex("$name = \\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(py)!!.groupValues[1]
        val wanted = (block("RAW_REQUIRED") + block("RAW_OPTIONAL")).lines()
            .mapNotNull { Regex("\"([a-z_]+)\"").find(it)?.groupValues?.get(1) }
        assertTrue("could not parse schema.py", wanted.size >= 15)
        val header = ExportSchema.SAMPLE_HEADER.split(",").toSet()
        val missing = wanted.filterNot { it in header }
        assertTrue("CSV header lacks $missing", missing.isEmpty())
    }
}
