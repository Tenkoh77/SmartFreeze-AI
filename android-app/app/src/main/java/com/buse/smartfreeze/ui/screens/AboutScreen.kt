package com.buse.smartfreeze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buse.smartfreeze.BuildConfig
import com.buse.smartfreeze.ui.components.InfoRow
import com.buse.smartfreeze.ui.components.SectionCard

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("SmartFreeze AI") {
            Text("Design and Development of an Intelligent Android Smartphone Performance Anomaly Detection and Freeze-Risk Warning System Using Machine Learning",
                style = MaterialTheme.typography.bodyMedium)
            InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
        SectionCard("Purpose") {
            Text("SmartFreeze AI learns the normal operating pattern of your phone from measurements Android makes available to ordinary apps, and uses an Isolation Forest machine-learning model to flag unusual conditions that may come before performance problems. Anomaly detection and freeze prediction are treated as separate questions; freeze-risk prediction is experimental.",
                style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Technologies") {
            Text("Kotlin · Jetpack Compose · Material 3 · MVVM · Coroutines/StateFlow · Room · WorkManager · Isolation Forest (scikit-learn, exported and re-implemented in Kotlin with a verified parity test)",
                style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Privacy") {
            Text("All data is processed and stored only on this device. The app has no internet permission, collects no message content, passwords or personal files, and excludes its data from cloud backup. Data leaves the phone only if you export it.",
                style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Academic attribution") {
            Text("Final-year Software Engineering project, Bindura University of Science Education (BUSE), Zimbabwe.", style = MaterialTheme.typography.bodyMedium)
            // TODO(student): replace the placeholders below with your name, registration number and supervisor.
            InfoRow("Student", "[Student name, Reg. No.]")
            InfoRow("Supervisor", "[Supervisor name]")
        }
    }
}
