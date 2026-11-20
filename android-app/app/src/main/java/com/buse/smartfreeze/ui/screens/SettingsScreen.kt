package com.buse.smartfreeze.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.data.repository.SettingsRepository
import com.buse.smartfreeze.monitoring.AlertNotifier
import com.buse.smartfreeze.monitoring.MonitoringMode
import com.buse.smartfreeze.monitoring.MonitoringService
import com.buse.smartfreeze.ui.SettingsViewModel
import com.buse.smartfreeze.ui.components.Banner
import com.buse.smartfreeze.ui.components.InfoRow
import com.buse.smartfreeze.ui.components.PersonalModelDetails
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.theme.LocalStatusColors

@Composable
fun SettingsScreen(vm: SettingsViewModel, onAbout: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val mon by vm.monitor.collectAsStateWithLifecycle()
    val count by vm.sampleCount.collectAsStateWithLifecycle()
    val device by vm.deviceInfo.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val share by vm.shareIntent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteModel by remember { mutableStateOf(false) }
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
    val canNotify = remember(s.notificationsEnabled) { AlertNotifier(context).canNotify() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(notificationsEnabled = granted) }
    }

    LaunchedEffect(share) {
        share?.let { context.startActivity(Intent.createChooser(it, "Export SmartFreeze data")); vm.shareIntent.value = null }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        message?.let { Banner(it, MaterialTheme.colorScheme.primary) }

        SectionCard("Monitoring") {
            Text("Sampling interval", style = MaterialTheme.typography.labelLarge)
            Choice(SettingsRepository.INTERVAL_CHOICES, s.intervalS, { "$it s" }) { v -> vm.update { it.copy(intervalS = v) } }
            Text("Applies to the next session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle("Battery-aware sampling", "Doubles the interval when the battery is at or below 20 % and not charging.", s.batteryAwareSampling) { v ->
                vm.update { it.copy(batteryAwareSampling = v) }
            }
            val bgRunning = mon.running && mon.mode == MonitoringMode.BACKGROUND
            Text("Background session", style = MaterialTheme.typography.labelLarge)
            Text("Normally monitoring pauses when you leave the app. A background session keeps sampling for up to 2 hours with a visible notification, and only when you start it here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (bgRunning) OutlinedButton(onClick = { MonitoringService.stop(context) }) { Text("Stop background session") }
            else Button(onClick = { MonitoringService.start(context) }) { Text("Start background session") }
        }

        SectionCard("Alerts") {
            Toggle("Notifications", if (canNotify) "Allowed by Android" else "Not allowed by Android: alerts stay in-app", s.notificationsEnabled) { v ->
                if (v && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canNotify) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.update { it.copy(notificationsEnabled = v) }
            }
            Text("Minimum severity to alert", style = MaterialTheme.typography.labelLarge)
            Choice(listOf("LOW", "MEDIUM", "HIGH"), s.minAlertSeverity, { it.lowercase().replaceFirstChar { c -> c.uppercase() } }) { v ->
                vm.update { it.copy(minAlertSeverity = v) }
            }
            Text("Repeat cooldown", style = MaterialTheme.typography.labelLarge)
            Choice(listOf(5, 10, 30, 60), s.cooldownMinutes, { "$it min" }) { v -> vm.update { it.copy(cooldownMinutes = v) } }
        }

        SectionCard("Data and privacy") {
            InfoRow("Stored samples", "$count")
            Text("Keep data for", style = MaterialTheme.typography.labelLarge)
            Choice(SettingsRepository.RETENTION_CHOICES, s.retentionDays, { "$it d" }) { v -> vm.update { it.copy(retentionDays = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::applyRetentionNow) { Text("Clean up now") }
                OutlinedButton(onClick = vm::export) { Text("Export CSV") }
            }
            OutlinedButton(onClick = { confirmDelete = true }, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = LocalStatusColors.current.critical)) { Text("Delete all monitoring data") }
            Toggle("Research mode", "Marks new sessions as research sessions (activity labels, probes).", s.researchMode) { v ->
                vm.update { it.copy(researchMode = v) }
            }
        }

        SectionCard("Appearance") {
            Choice(listOf("SYSTEM", "LIGHT", "DARK"), s.themeMode, { it.lowercase().replaceFirstChar { c -> c.uppercase() } }) { v ->
                vm.update { it.copy(themeMode = v) }
            }
        }

        PersonalModelDetails(model, baseline, training, vm.policy, onRetrain = { vm.trainNow() }, onDelete = { confirmDeleteModel = true })

        SectionCard("Device") {
            device?.let {
                InfoRow("Device", "${it.manufacturer} ${it.model}")
                InfoRow("Android API level", "${it.sdkInt}")
            }
            TextButton(onClick = onAbout) { Text("About SmartFreeze AI") }
        }
    }

    if (confirmDeleteModel) {
        AlertDialog(
            onDismissRequest = { confirmDeleteModel = false },
            title = { Text("Delete your personal model?") },
            text = { Text("The model trained on this phone is removed. Your recorded data is kept, so the model can be trained again from it.") },
            confirmButton = { TextButton(onClick = { confirmDeleteModel = false; vm.deletePersonalModel() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteModel = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all monitoring data?") },
            text = { Text("This permanently removes all sessions, samples, detections, alerts and slowdown events from this phone. Settings are kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAll() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected, onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label(o)) }
        }
    }
}
