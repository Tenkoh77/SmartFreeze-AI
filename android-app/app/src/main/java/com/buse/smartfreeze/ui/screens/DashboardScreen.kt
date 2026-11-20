package com.buse.smartfreeze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.DetectionSource
import com.buse.smartfreeze.ui.Format
import com.buse.smartfreeze.ui.MonitorViewModel
import com.buse.smartfreeze.ui.components.Banner
import com.buse.smartfreeze.ui.components.LearningCard
import com.buse.smartfreeze.ui.components.MetricTile
import com.buse.smartfreeze.ui.components.Pill
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.components.StatusDot
import com.buse.smartfreeze.ui.components.Trend
import com.buse.smartfreeze.ui.components.severityColor
import com.buse.smartfreeze.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(vm: MonitorViewModel, onOpenInsights: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val samples by vm.recentSamples.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
    val r = st.lastReading
    val status = LocalStatusColors.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.running) { while (st.running) { now = System.currentTimeMillis(); delay(1000) } }

    // Samples arrive newest-first; reverse for chronological sparklines.
    val memSeries = samples.asReversed().map { it.memAvailBytes.toDouble() / it.memTotalBytes }
    val memTrend = trendOf(memSeries.takeLast(12))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Monitoring status and controls
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, color) = when {
                    !st.running -> "Not monitoring" to MaterialTheme.colorScheme.outline
                    st.pausedReason != null -> st.pausedReason!! to status.notice
                    else -> "Monitoring" to status.ok
                }
                StatusDot(color)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ModelChip(model)
            }
            if (st.running) {
                Text(
                    "Session #${st.sessionId} · ${Format.duration(now - (st.startedAtMs ?: now))} · every ${st.effectiveIntervalS}s" +
                        (if (st.batterySaving) " (battery saver)" else "") + " · ${st.samplesTaken} samples",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!st.running) {
                    Button(onClick = vm::start) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start monitoring") }
                } else {
                    OutlinedButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
                    val userPaused = st.pausedReason == "Paused by you"
                    FilledTonalButton(onClick = { vm.setPaused(!userPaused) }) {
                        Icon(if (userPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                        Spacer(Modifier.width(6.dp)); Text(if (userPaused) "Resume" else "Pause")
                    }
                }
            }
        }

        // Baseline progress until this phone has its own personal model
        LearningCard(model, baseline, training, vm.policy) { vm.trainNow() }

        // Overall device-condition summary (the AI conclusion, clearly attributed)
        val res = st.lastResult
        SectionCard("Device condition") {
            when {
                res == null -> Text(
                    if (st.running) "Waiting for the first measurement…" else "Start monitoring to assess the device condition.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    val sevColor = if (res.category == AlertCategory.NORMAL) status.ok else severityColor(res.severity)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(res.category.label, style = MaterialTheme.typography.titleLarge, color = sevColor, modifier = Modifier.weight(1f))
                        res.severity?.let { Pill(it.name, sevColor) }
                    }
                    Text(res.explanation, style = MaterialTheme.typography.bodyMedium)
                    if (res.source == DetectionSource.ISOLATION_FOREST) {
                        Text("Anomaly score ${Format.score(res.score)} (threshold ${Format.score(res.threshold)}) · model ${res.modelName}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Banner("Learning your phone: no personal model yet, so this is a rule-based check, not an AI prediction.", status.notice)
                    }
                    OutlinedButton(onClick = onOpenInsights) { Text("Why? See AI insights") }
                }
            }
        }

        // Measured facts
        Text("Measured now", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                "Available RAM", Format.bytes(r?.memAvailBytes),
                r?.let { "${Format.pct(it.memAvailBytes.toDouble() / it.memTotalBytes)} of ${Format.bytes(it.memTotalBytes)}" },
                Icons.Filled.Memory, Modifier.weight(1f), trend = memTrend, sparkline = memSeries.takeLast(40),
            )
            val pressureTone = when {
                r == null -> null
                r.lowMemory -> status.critical
                r.memAvailBytes - r.memThresholdBytes < 0.05 * r.memTotalBytes -> status.warning
                else -> status.ok
            }
            MetricTile(
                "Memory pressure",
                when { r == null -> "—"; r.lowMemory -> "Low"; pressureTone == status.warning -> "Elevated"; else -> "Normal" },
                r?.let { "Android low-memory line: ${Format.bytes(it.memThresholdBytes)}" },
                Icons.Filled.Speed, Modifier.weight(1f), tone = pressureTone,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val t = r?.thermalStatus
            MetricTile(
                "Thermal status", Format.thermal(t).let { if (r == null) "—" else it },
                r?.thermalHeadroom?.let { "Headroom %.2f (1.0 = throttling)".format(it) } ?: "Headroom not reported",
                Icons.Filled.DeviceThermostat, Modifier.weight(1f),
                tone = when { t == null -> null; t >= 3 -> status.critical; t == 2 -> status.warning; t == 1 -> status.notice; else -> status.ok },
            )
            MetricTile(
                "Battery", r?.batteryLevelPct?.let { "$it%" } ?: "—",
                r?.let { (if (it.isCharging == true) "Charging" else "Not charging") + (it.batteryTempC?.let { c -> " · %.1f °C".format(c) } ?: "") },
                if (r?.isCharging == true) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd, Modifier.weight(1f),
            )
        }
        MetricTile(
            "Free storage", Format.bytes(r?.storageAvailBytes),
            r?.storageTotalBytes?.let { tot -> "${Format.pct((r.storageAvailBytes ?: 0).toDouble() / tot)} of ${Format.bytes(tot)} free" },
            Icons.Filled.SdStorage, Modifier.fillMaxWidth(),
            tone = r?.storageTotalBytes?.let { tot -> if ((r.storageAvailBytes ?: tot).toDouble() / tot < 0.1) status.warning else null },
        )
    }
}

@Composable
fun ModelChip(model: ModelStatus) {
    val status = LocalStatusColors.current
    when (model) {
        is ModelStatus.Ready -> when {
            model.bundle.isDemoQuality -> Pill("Demo-quality AI", status.notice)
            model.bundle.isOnDevice -> Pill("Personal AI", status.ok)
            model.bundle.syntheticTrainingData -> Pill("Demo model", status.notice)
            else -> Pill("Team model", status.ok)
        }
        ModelStatus.Loading -> Pill("Loading model", MaterialTheme.colorScheme.outline)
        ModelStatus.NotAvailable -> Pill("Learning your phone", status.notice)
        is ModelStatus.Invalid -> Pill("Model error", status.critical)
    }
}

fun trendOf(values: List<Double>): Trend? {
    if (values.size < 4) return null
    val half = values.size / 2
    val a = values.take(half).average()
    val b = values.drop(half).average()
    return when { b - a > 0.01 -> Trend.UP; a - b > 0.01 -> Trend.DOWN; else -> Trend.FLAT }
}
