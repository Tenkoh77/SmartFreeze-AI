package com.buse.smartfreeze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.DetectionSource
import com.buse.smartfreeze.ui.Format
import com.buse.smartfreeze.ui.MonitorViewModel
import com.buse.smartfreeze.ui.components.Banner
import com.buse.smartfreeze.ui.components.InfoRow
import com.buse.smartfreeze.ui.components.Pill
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.components.severityColor
import com.buse.smartfreeze.ui.theme.LocalStatusColors
import kotlin.math.abs

@Composable
fun InsightsScreen(vm: MonitorViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val stored by vm.latestAnomaly.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    val res = st.lastResult
    val r = st.lastReading

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (model as? ModelStatus.Ready)?.takeIf { it.bundle.isDemoQuality }?.let {
            Banner("Demo-quality model: trained on this phone from a short quick baseline. Treat results as a demonstration; " +
                "the full personal model trains automatically once enough normal data exists.", status.notice)
        }
        (model as? ModelStatus.Ready)?.takeIf { it.bundle.syntheticTrainingData }?.let {
            Banner("Demo model: trained on SYNTHETIC test data to demonstrate the software. Retrain on your own recordings before drawing conclusions.", status.notice)
        }

        SectionCard("Measured facts") {
            Text("Direct readings from Android APIs. No interpretation.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r == null) Text("No measurement yet.") else {
                InfoRow("Available memory", "${Format.bytes(r.memAvailBytes)} (${Format.pct(r.memAvailBytes.toDouble() / r.memTotalBytes)})")
                InfoRow("Android low-memory flag", if (r.lowMemory) "Yes" else "No")
                InfoRow("Thermal status", Format.thermal(r.thermalStatus))
                InfoRow("Battery temperature", r.batteryTempC?.let { "%.1f °C".format(it) } ?: "Not available")
                InfoRow("Free storage", Format.bytes(r.storageAvailBytes))
                InfoRow("Activity context", Format.activity(st.activity))
            }
        }

        SectionCard("AI assessment") {
            if (res == null) {
                Text("No assessment yet. Start monitoring to receive one.")
                return@SectionCard
            }
            if (res.source == DetectionSource.RULE_BASED_COLD_START) {
                Banner("Learning your phone: no personal model has been trained yet. The result below comes from fixed rules, not machine learning.", status.notice)
            }
            val color = if (res.category == AlertCategory.NORMAL) status.ok else severityColor(res.severity)
            Row { Text(res.category.label, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.weight(1f)); res.severity?.let { Pill(it.name, color) } }
            if (res.source == DetectionSource.ISOLATION_FOREST) {
                InfoRow("Anomaly score", Format.score(res.score))
                InfoRow("Decision threshold", Format.score(res.threshold))
                InfoRow("Classification", if (res.isAnomalous) "Anomalous" else "Normal")
                InfoRow("Model used", (res.modelName ?: "—") + if (res.modelName == "general" && st.activity != null) " (no activity model for this activity)" else "")
                InfoRow("Model version", res.modelVersion ?: "—")
            }
            Text(res.explanation, style = MaterialTheme.typography.bodyMedium)
        }

        val devs = res?.deviations.orEmpty()
        if (devs.isNotEmpty()) {
            SectionCard("Most unusual readings vs your baseline") {
                Text("A descriptive comparison with your typical (median) values. It shows what differs, but it is not proof of what caused it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                devs.forEach { d ->
                    Column {
                        Text(Format.featureLabel(d.featureName), fontWeight = FontWeight.Medium)
                        Text("now %.3f · typical %.3f · %.1f× usual spread %s".format(d.value, d.baselineMedian, abs(d.robustZ),
                            if (d.robustZ > 0) "above" else "below"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (res != null && res.recommendations.isNotEmpty()) {
            SectionCard("Suggested actions") {
                res.recommendations.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Text("SmartFreeze AI never closes apps or changes settings for you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        stored?.let { a ->
            SectionCard("Last stored detection") {
                InfoRow("When", Format.dateTime(a.timestampMs))
                InfoRow("Category", a.category.replace('_', ' ').lowercase())
                InfoRow("Source", if (a.source == DetectionSource.ISOLATION_FOREST.name) "Isolation Forest" else "Rule-based")
                if (a.isSimulated) Pill("SIMULATION", status.notice)
                Text(a.explanation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
