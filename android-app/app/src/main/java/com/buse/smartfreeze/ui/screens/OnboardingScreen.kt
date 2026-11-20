package com.buse.smartfreeze.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.buse.smartfreeze.ui.MonitorViewModel
import com.buse.smartfreeze.ui.SettingsViewModel
import com.buse.smartfreeze.ui.components.SectionCard
import com.buse.smartfreeze.ui.theme.LocalStatusColors

@Composable
fun OnboardingScreen(settingsVm: SettingsViewModel, monitorVm: MonitorViewModel, onDone: () -> Unit) {
    // Notification permission is optional; monitoring works without it (alerts stay in-app).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settingsVm.update { s -> s.copy(notificationsEnabled = it) }
        settingsVm.completeOnboarding()
        monitorVm.start()
        onDone()
    }
    val begin = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            settingsVm.completeOnboarding(); monitorVm.start(); onDone()
        }
    }
    val status = LocalStatusColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)),
                RoundedCornerShape(28.dp),
            ).padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.MonitorHeart, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
                Text("SmartFreeze AI", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
                Text("Learns your phone's normal performance pattern and tells you when conditions become unusual.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }

        SectionCard("What SmartFreeze AI measures") {
            Bullet(Icons.Filled.CheckCircle, "System memory available, and Android's own low-memory signal", status.ok)
            Bullet(Icons.Filled.CheckCircle, "Thermal status and, where supported, thermal headroom", status.ok)
            Bullet(Icons.Filled.CheckCircle, "Battery level, charging state and temperature", status.ok)
            Bullet(Icons.Filled.CheckCircle, "Free storage space", status.ok)
            Bullet(Icons.Filled.CheckCircle, "SmartFreeze's own memory and CPU use, to measure its overhead", status.ok)
        }
        SectionCard("What it cannot see") {
            Bullet(Icons.Filled.Block, "Other apps' CPU or memory, process lists or system logs: Android does not allow this without root", MaterialTheme.colorScheme.onSurfaceVariant)
            Bullet(Icons.Filled.Block, "The content of your messages, photos, passwords or browsing", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Privacy") {
            Bullet(Icons.Filled.Lock, "Everything is processed and stored on this phone. The app has no internet permission.", MaterialTheme.colorScheme.primary)
            Bullet(Icons.Filled.Lock, "Data leaves the phone only if you export it, and you can delete it at any time in Settings.", MaterialTheme.colorScheme.primary)
        }
        SectionCard("Permissions") {
            Bullet(Icons.Filled.Notifications, "Notifications (optional): to show alerts when an unusual condition is detected. You can decline; alerts will still appear inside the app.", MaterialTheme.colorScheme.primary)
            Text("No other permissions are requested.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "SmartFreeze AI first learns what is normal for YOUR phone: after about 6 hours of monitoring spread over 3 days, it trains a personal AI model on the phone itself. No data leaves the phone. Until then it uses clearly labelled rule-based checks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = begin, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Begin monitoring") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
