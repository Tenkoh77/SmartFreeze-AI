package com.buse.smartfreeze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.domain.model.Severity
import com.buse.smartfreeze.ui.Format
import com.buse.smartfreeze.ui.MonitorViewModel
import com.buse.smartfreeze.ui.components.Banner
import com.buse.smartfreeze.ui.components.InfoRow
import com.buse.smartfreeze.ui.components.LineChart
import com.buse.smartfreeze.ui.components.Pill
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.components.severityColor
import com.buse.smartfreeze.ui.theme.LocalStatusColors

@Composable
fun LiveScreen(vm: MonitorViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val samples by vm.recentSamples.collectAsStateWithLifecycle()
    val events by vm.sessionAnomalies.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    val chrono = samples.asReversed()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer16() }
        if (!st.running) item { Banner("Monitoring is stopped. Start it from the Dashboard to see live data.", status.notice) }
        item {
            SectionCard("Session") {
                InfoRow("Session", st.sessionId?.let { "#$it" } ?: "—")
                InfoRow("Duration", st.startedAtMs?.let { Format.duration(System.currentTimeMillis() - it) } ?: "—")
                InfoRow("Sampling interval", "${st.configuredIntervalS} s" + if (st.batterySaving) " (now ${st.effectiveIntervalS} s, battery saver)" else "")
                InfoRow("Samples / duplicates skipped", "${st.samplesTaken} / ${st.duplicatesSkipped}")
                InfoRow("Activity label", Format.activity(st.activity))
                InfoRow("Last inference time", st.lastInferenceMicros?.let { "$it µs" } ?: "—")
            }
        }
        item {
            SectionCard("Available memory (% of total)") {
                LineChart(chrono.map { it.timestampMs to it.memAvailBytes.toDouble() / it.memTotalBytes },
                    yMin = 0.0, yMax = 1.0, description = "Line chart of available memory ratio over this session")
            }
        }
        item {
            SectionCard("Anomaly score") {
                if (st.recentScores.isEmpty()) {
                    Text("No machine-learning scores yet (baseline collection or no samples).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LineChart(st.recentScores, threshold = st.lastResult?.threshold, color = MaterialTheme.colorScheme.tertiary,
                        description = "Line chart of Isolation Forest anomaly score with dashed decision threshold")
                    Text("Dashed line = decision threshold. Higher score = more unusual. It is not a probability of freezing.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionCard("Thermal status (0 = none … 6 = shutdown)") {
                LineChart(chrono.mapNotNull { s -> s.thermalStatus?.let { s.timestampMs to it.toDouble() } },
                    yMin = 0.0, yMax = 6.0, color = status.warning, height = 110.dp,
                    description = "Line chart of thermal status over this session")
            }
        }
        item { Text("Detected events this session", style = MaterialTheme.typography.titleSmall) }
        if (events.isEmpty()) item {
            Text("None so far.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(events, key = { it.id }) { e ->
            val sev = e.severity?.let { runCatching { Severity.valueOf(it) }.getOrNull() }
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.time(e.timestampMs), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (e.isSimulated) Pill("SIMULATION", status.notice)
                    sev?.let { Pill(it.name, severityColor(it)) }
                }
                Text(e.category.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } +
                    (e.score?.let { " · score ${Format.score(it)}" } ?: " · rule-based"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        item { Spacer16() }
    }
}

@Composable
internal fun Spacer16() = androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
