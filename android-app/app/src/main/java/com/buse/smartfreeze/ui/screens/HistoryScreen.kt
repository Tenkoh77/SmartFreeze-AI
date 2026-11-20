package com.buse.smartfreeze.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.domain.model.Severity
import com.buse.smartfreeze.ui.Format
import com.buse.smartfreeze.ui.HistoryRange
import com.buse.smartfreeze.ui.HistoryViewModel
import com.buse.smartfreeze.ui.components.InfoRow
import com.buse.smartfreeze.ui.components.LineChart
import com.buse.smartfreeze.ui.components.Pill
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.components.severityColor
import com.buse.smartfreeze.ui.theme.LocalStatusColors

@Composable
fun HistoryScreen(vm: HistoryViewModel, onExport: () -> Unit) {
    val range by vm.range.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val daily by vm.daily.collectAsStateWithLifecycle()
    val samples by vm.samples.collectAsStateWithLifecycle()
    val anomalies by vm.anomalies.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    // Downsample for drawing: at most ~400 points regardless of range.
    val memPoints = remember(samples) {
        val step = (samples.size / 400).coerceAtLeast(1)
        samples.filterIndexed { i, _ -> i % step == 0 }.map { it.timestampMs to it.memAvailBytes.toDouble() / it.memTotalBytes }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { r -> FilterChip(selected = r == range, onClick = { vm.range.value = r }, label = { Text(r.label) }) }
            }
        }
        if (activities.isNotEmpty()) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = activity == null, onClick = { vm.activity.value = null }, label = { Text("All activities") })
                activities.forEach { a -> FilterChip(selected = activity == a, onClick = { vm.activity.value = a }, label = { Text(Format.activity(a)) }) }
            }
        }
        item {
            SectionCard("Available memory over time") {
                LineChart(memPoints, yMin = 0.0, yMax = 1.0, description = "Historical available memory ratio")
                Text("${samples.size} stored samples in range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionCard("Daily summary") {
                if (daily.isEmpty()) Text("No data in this range.")
                daily.forEach { InfoRow(it.day, "${it.samples} samples · ${it.anomalies} anomalies") }
            }
        }
        item {
            SectionCard("Sessions") {
                if (sessions.isEmpty()) Text("No sessions in this range.")
                sessions.forEach { s ->
                    Column {
                        Text("#${s.id} · ${Format.dateTime(s.startedAtMs)}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${s.endedAtMs?.let { Format.duration(it - s.startedAtMs) } ?: "running"} · ${s.sampleCount} samples · " +
                                "${s.anomalyCount} anomalies · ${s.mode}${if (s.isResearch) " · research" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Text("Event timeline", style = MaterialTheme.typography.titleSmall) }
        if (anomalies.isEmpty()) item { Text("No detections in this range.", style = MaterialTheme.typography.bodySmall) }
        items(anomalies.take(200), key = { it.id }) { a ->
            val sev = a.severity?.let { runCatching { Severity.valueOf(it) }.getOrNull() }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Format.dateTime(a.timestampMs), style = MaterialTheme.typography.labelLarge)
                    Text(a.category.replace('_', ' ').lowercase() + " · " + Format.activity(a.activityLabel),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (a.isSimulated) Pill("SIM", status.notice)
                sev?.let { Pill(it.name, severityColor(it)) }
            }
            HorizontalDivider()
        }
        item { OutlinedButton(onClick = onExport, modifier = Modifier.padding(vertical = 12.dp)) { Text("Export report data (CSV)") } }
    }
}
