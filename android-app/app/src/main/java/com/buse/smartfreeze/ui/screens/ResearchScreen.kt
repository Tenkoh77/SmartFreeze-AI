package com.buse.smartfreeze.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.research.ControlledWorkload
import com.buse.smartfreeze.research.ResponsivenessProbe
import com.buse.smartfreeze.ui.Format
import com.buse.smartfreeze.ui.ResearchViewModel
import com.buse.smartfreeze.ui.components.Banner
import com.buse.smartfreeze.ui.components.InfoRow
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchScreen(vm: ResearchViewModel) {
    val mon by vm.monitor.collectAsStateWithLifecycle()
    val probe by vm.probe.collectAsStateWithLifecycle()
    val work by vm.workload.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val eventCount by vm.eventCount.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exits = remember { ResponsivenessProbe.ownExitReasons(context) }
    var tapStart by remember { mutableLongStateOf(0L) }

    // Frame timing is measured only while this screen is visible.
    DisposableEffect(Unit) {
        (context as? Activity)?.window?.let { vm.probeRef.attachFrameMetrics(it) }
        onDispose { vm.probeRef.detachFrameMetrics() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Banner("RESEARCH MODE: tools for collecting labelled experimental data. Workloads and probes affect only SmartFreeze AI itself.", status.notice)

        SectionCard("Session and activity label") {
            InfoRow("Monitoring session", mon.sessionId?.let { "#$it" } ?: "Not running (start it on the Dashboard)")
            Text("Select what you are doing now. Labels are stored with every sample and are never guessed by the app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mon.activity == null, onClick = { vm.setActivity(null) }, label = { Text("None") })
                Format.ACTIVITIES.forEach { (key, label) ->
                    FilterChip(selected = mon.activity == key, onClick = { vm.setActivity(key) }, label = { Text(label) })
                }
            }
        }

        SectionCard("Responsiveness ground truth") {
            Text("Slowdown event = main-thread probe late > ${ResponsivenessProbe.SLOW_MS} ms, a frame > ${ResponsivenessProbe.SLOW_MS} ms, a slow tap response, or your manual report.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoRow("Probe", if (probe.probing) "Running" else "Stopped")
            InfoRow("Probes run / max lateness", "${probe.probesRun} / ${probe.maxLatenessMs} ms")
            InfoRow("Slow frames on this screen", "${probe.slowFrames}")
            InfoRow("Last tap-to-frame latency", probe.lastTapLatencyMs?.let { "$it ms" } ?: "—")
            InfoRow("Slowdown events recorded (all time)", "$eventCount")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (probe.probing) OutlinedButton(onClick = vm::stopProbe) { Text("Stop probe") }
                else Button(onClick = vm::startProbe) { Text("Start probe") }
                FilledTonalButton(onClick = {
                    tapStart = System.nanoTime()
                    scope.launch {
                        withFrameNanos { }                      // next frame after the tap has been handled
                        vm.tapLatency((System.nanoTime() - tapStart) / 1_000_000)
                    }
                }) { Text("Tap test") }
            }
            Button(onClick = vm::manualLag, modifier = Modifier.fillMaxWidth()) { Text("I noticed lag now") }
        }

        SectionCard("Controlled workloads (this app only)") {
            Text("Create reproducible unusual conditions for experiments. Each stops automatically after 2 minutes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoRow("Running", work.running?.label?.let { "$it · ${work.detail}" } ?: "None")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlledWorkload.Kind.entries.forEach { k ->
                    OutlinedButton(onClick = { vm.startWorkload(k) }, enabled = work.running == null) { Text(k.label) }
                }
            }
            if (work.running != null) Button(onClick = vm::stopWorkload) { Text("Stop workload") }
        }

        SectionCard("Simulation (for demonstration)") {
            Text("Feeds a synthetic memory-and-heat episode through the model and creates an alert labelled SIMULATION. It is never shown as a live detection.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::injectSimulation, enabled = mon.running) { Text("Inject simulated episode") }
        }

        SectionCard("Quick demo baseline (for demonstrations)") {
            Text("Trains a personal model from as little as ${vm.policy.demoMinMinutes.toInt()} minutes of monitoring, so the AI can be shown " +
                "without waiting days. The model is labelled DEMO-QUALITY everywhere and is replaced automatically by the full " +
                "personal model once the normal baseline is complete.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoRow("Recorded so far", "≈ %.0f min · %d samples".format(baseline.monitoredSeconds / 60.0, baseline.samples))
            training.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { vm.trainNow(demo = true) }, enabled = vm.policy.isDemoReady(baseline) && !training.running) {
                Text(if (training.running) "Training…" else "Train quick demo model")
            }
        }

        SectionCard("Model and training status") {
            when (val m = model) {
                is ModelStatus.Ready -> {
                    InfoRow("Model version", m.bundle.modelVersion)
                    InfoRow("Trained", m.bundle.createdUtc)
                    InfoRow("Origin", when {
                        m.bundle.isDemoQuality -> "Trained on this phone (demo-quality)"
                        m.bundle.isOnDevice -> "Trained on this phone (personal)"
                        else -> "Shipped with the app (team starter model)"
                    })
                    InfoRow("Training data", if (m.bundle.syntheticTrainingData) "SYNTHETIC (demo only)" else "Recorded device data")
                    (m.bundle.metadata["total_training_ms"] as? Number)?.let { InfoRow("On-device training time", "${it.toLong()} ms") }
                    (m.bundle.metadata["split_method"] as? String)?.let { InfoRow("Validation split", it) }
                    InfoRow("Window", "${m.bundle.windowSeconds} s")
                    InfoRow("Activity models", m.bundle.activityModels.keys.sorted().joinToString().ifEmpty { "none (general only)" })
                    InfoRow("Load time", "${m.loadMillis} ms")
                    InfoRow("Slowdown-risk model", "Not installed (experimental; requires validated data)")
                }
                ModelStatus.NotAvailable -> Text("No model yet: learning this phone's baseline (rule-based checks only).")
                ModelStatus.Loading -> Text("Loading…")
                is ModelStatus.Invalid -> Text("Model rejected: ${m.reason}")
            }
        }

        SectionCard("This app's recent exits (Android 11+)") {
            if (exits.isEmpty()) Text("None reported.", style = MaterialTheme.typography.bodySmall)
            exits.forEach { InfoRow(Format.dateTime(it.timestamp), ResponsivenessProbe.exitReasonName(it.reason)) }
        }

        SectionCard("Recent slowdown events") {
            if (events.isEmpty()) Text("None recorded.", style = MaterialTheme.typography.bodySmall)
            events.forEach { InfoRow(Format.time(it.timestampMs) + " · " + it.eventType, it.latencyMs?.let { l -> "$l ms" } ?: it.source) }
        }
    }
}
