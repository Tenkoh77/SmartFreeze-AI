package com.buse.smartfreeze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.domain.model.Severity
import com.buse.smartfreeze.ui.AlertsViewModel
import com.buse.smartfreeze.ui.Format
import com.buse.smartfreeze.ui.components.Pill
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.components.severityColor
import com.buse.smartfreeze.ui.theme.LocalStatusColors

@Composable
fun AlertsScreen(vm: AlertsViewModel) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    if (alerts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.NotificationsNone, null, tint = MaterialTheme.colorScheme.outline)
                Text("No alerts yet", style = MaterialTheme.typography.titleMedium)
                Text("Alerts appear here when monitoring finds an unusual condition.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${alerts.count { !it.acknowledged }} unacknowledged", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.acknowledgeAll() }) { Text("Acknowledge all") }
            }
        }
        items(alerts, key = { it.id }) { a ->
            val sev = runCatching { Severity.valueOf(a.severity) }.getOrNull()
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        fontWeight = if (a.acknowledged) FontWeight.Normal else FontWeight.SemiBold)
                    sev?.let { Pill(it.name, severityColor(it)) }
                }
                Text(Format.dateTime(a.timestampMs) + if (a.isPrediction) " · prediction" else " · observation",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (a.isSimulated) Pill("SIMULATION: injected test data, not a live detection", status.notice)
                Text(a.message, style = MaterialTheme.typography.bodyMedium)
                if (a.affectedIndicators.isNotBlank()) {
                    Text("Affected indicators: " + a.affectedIndicators.split(", ").joinToString(", ") { Format.featureLabel(it) },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (a.recommendation.isNotBlank()) Text("Recommended: ${a.recommendation}", style = MaterialTheme.typography.bodySmall)
                Row {
                    Text(if (a.notified) "Notification sent" else "In-app only", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                    if (!a.acknowledged) TextButton(onClick = { vm.acknowledge(a.id) }) { Text("Acknowledge") }
                    else Text("Acknowledged", style = MaterialTheme.typography.labelMedium, color = status.ok)
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp)) }
    }
}
