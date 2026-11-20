package com.buse.smartfreeze.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.data.training.TrainingState
import com.buse.smartfreeze.domain.BaselineStats
import com.buse.smartfreeze.domain.TrainingPolicy
import com.buse.smartfreeze.ml.ModelBundle
import com.buse.smartfreeze.ui.Format
import com.buse.smartfreeze.ui.theme.LocalStatusColors

/** The active personal (full, not demo) model, if any. */
fun personalModel(m: ModelStatus): ModelBundle? =
    (m as? ModelStatus.Ready)?.bundle?.takeIf { it.origin == ModelBundle.ORIGIN_ON_DEVICE }

@Composable
fun BaselineProgress(stats: BaselineStats, policy: TrainingPolicy) {
    val p = policy.progress(stats).toFloat()
    LinearProgressIndicator(
        progress = { p },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Baseline ${(p * 100).toInt()} percent complete" },
    )
    Text(
        "≈ %.1f of %.0f h monitored · %d of %d samples · %d of %d days".format(
            stats.monitoredHours, policy.minHours, stats.samples, policy.minSamples, stats.days, policy.minDays),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TrainingMessage(training: TrainingState) {
    training.message?.let {
        val c = LocalStatusColors.current
        Text(it, style = MaterialTheme.typography.bodySmall, color = if (training.failed) c.warning else MaterialTheme.colorScheme.primary)
    }
    if (training.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

/** Dashboard card shown until the phone has its own full personal model. */
@Composable
fun LearningCard(model: ModelStatus, stats: BaselineStats, training: TrainingState, policy: TrainingPolicy, onTrain: () -> Unit) {
    if (personalModel(model) != null) return
    val demoActive = (model as? ModelStatus.Ready)?.bundle?.isDemoQuality == true
    SectionCard(if (demoActive) "Still learning your phone" else "Learning your phone") {
        Text(
            if (demoActive) "A demo-quality model is active. The full personal model trains automatically when the baseline below is complete."
            else "SmartFreeze AI is learning what normal looks like on THIS phone. Use your phone as usual with monitoring on; " +
                "your personal AI model trains automatically on the phone when this reaches 100 %.",
            style = MaterialTheme.typography.bodyMedium,
        )
        BaselineProgress(stats, policy)
        val remaining = policy.remaining(stats)
        if (remaining.isNotEmpty()) {
            Text("Still needed: " + remaining.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!demoActive) Text("Until then, warnings come from simple rule-based checks, not machine learning.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TrainingMessage(training)
        if (policy.isReady(stats) && !training.running) Button(onClick = onTrain) { Text("Train my personal model now") }
    }
}

/** Settings section: details of the personal model, retrain and delete. */
@Composable
fun PersonalModelDetails(
    model: ModelStatus, stats: BaselineStats, training: TrainingState, policy: TrainingPolicy,
    onRetrain: () -> Unit, onDelete: () -> Unit,
) {
    val b = (model as? ModelStatus.Ready)?.bundle
    SectionCard("AI model") {
        when {
            b == null -> {
                InfoRow("Status", if (model is ModelStatus.Invalid) "Model rejected: ${model.reason}" else "Learning your phone (no model yet)")
                BaselineProgress(stats, policy)
            }
            else -> {
                InfoRow("Type", when {
                    b.isDemoQuality -> "Personal, demo-quality (quick baseline)"
                    b.isOnDevice -> "Personal (trained on this phone)"
                    else -> "Team starter model (shipped with the app)"
                })
                InfoRow("Version", b.modelVersion)
                (b.metadata["trained_at_ms"] as? Number)?.let { InfoRow("Trained", Format.dateTime(it.toLong())) }
                (b.metadata["training_samples"] as? Number)?.let { InfoRow("Samples used", "%,d".format(it.toLong())) }
                (b.metadata["baseline_days"] as? Number)?.let { InfoRow("Days of data", "${it.toInt()}") }
                (b.metadata["total_training_ms"] as? Number)?.let { InfoRow("Training time on this phone", "%.2f s".format(it.toDouble() / 1000)) }
                InfoRow("Decision threshold", "%.3f".format(b.general.scoreThreshold))
                InfoRow("Activity models", b.activityModels.keys.sorted().joinToString { Format.activity(it) }.ifEmpty { "none (general model only)" })
                if (b.isOnDevice) Text("Retrains automatically every ${policy.retrainAfterDays} days when enough new data exists.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TrainingMessage(training)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetrain, enabled = policy.isReady(stats) && !training.running) {
                Text(if (b?.isOnDevice == true && !b.isDemoQuality) "Retrain now" else "Train now")
            }
            if (b?.isOnDevice == true) OutlinedButton(onClick = onDelete, enabled = !training.running) { Text("Delete personal model") }
        }
        if (!policy.isReady(stats)) Text("Training needs a complete baseline (${policy.minHours.toInt()} h over ${policy.minDays} days).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

