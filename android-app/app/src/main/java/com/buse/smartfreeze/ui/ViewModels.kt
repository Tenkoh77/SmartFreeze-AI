package com.buse.smartfreeze.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.buse.smartfreeze.AppContainer
import com.buse.smartfreeze.SmartFreezeApp
import com.buse.smartfreeze.data.db.AlertEntity
import com.buse.smartfreeze.data.db.AnomalyEntity
import com.buse.smartfreeze.data.db.DailyCount
import com.buse.smartfreeze.data.db.SampleEntity
import com.buse.smartfreeze.data.db.SessionSummary
import com.buse.smartfreeze.data.db.SettingsEntity
import com.buse.smartfreeze.data.db.SlowdownEventEntity
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.data.training.TrainingState
import com.buse.smartfreeze.domain.BaselineStats
import com.buse.smartfreeze.domain.TrainingPolicy
import com.buse.smartfreeze.domain.model.Severity
import com.buse.smartfreeze.monitoring.MonitoringState
import com.buse.smartfreeze.research.ControlledWorkload
import com.buse.smartfreeze.research.ResponsivenessProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun <T> kotlinx.coroutines.flow.Flow<T>.state(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** Shared by Dashboard, Live and Insights: engine state + model status + latest stored anomaly. */
class MonitorViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<MonitoringState> = c.engine.state
    val model: StateFlow<ModelStatus> = c.models.status
    val settings = c.settings.settings.state(this, SettingsEntity())
    val latestAnomaly = c.monitoring.anomalies.latest().state(this, null)
    val unacknowledgedAlerts = c.monitoring.alerts.unacknowledgedCount().state(this, 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionAnomalies: StateFlow<List<AnomalyEntity>> = c.engine.state
        .map { it.sessionId }.distinctUntilChanged()
        .flatMapLatest { id -> id?.let { c.monitoring.anomalies.recentForSession(it, 50) } ?: flowOf(emptyList()) }
        .state(this, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSamples: StateFlow<List<SampleEntity>> = c.engine.state
        .map { it.sessionId }.distinctUntilChanged()
        .flatMapLatest { id -> id?.let { c.monitoring.samples.recent(it, 180) } ?: flowOf(emptyList()) }
        .state(this, emptyList())

    val baseline: StateFlow<BaselineStats> = c.trainer.baselineStats().state(this, BaselineStats())
    val training: StateFlow<TrainingState> = c.trainer.state
    val policy: TrainingPolicy get() = c.trainer.policy
    fun trainNow(demo: Boolean = false) = c.trainer.trainNow(demo)

    fun start() = c.engine.start()
    fun stop() = c.engine.stop()
    fun setPaused(p: Boolean) = c.engine.setUserPaused(p)
    fun setActivity(label: String?) = c.engine.setActivity(label)
}

enum class HistoryRange(val label: String, val days: Int) { TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30) }

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val range = MutableStateFlow(HistoryRange.WEEK)
    val activity = MutableStateFlow<String?>(null)

    private fun from(r: HistoryRange) = System.currentTimeMillis() - r.days * 86_400_000L

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessions: StateFlow<List<SessionSummary>> = range.flatMapLatest { c.monitoring.sessions.summaries(from(it), Long.MAX_VALUE) }.state(this, emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val daily: StateFlow<List<DailyCount>> = range.flatMapLatest { c.monitoring.samples.dailyCounts(from(it)) }.state(this, emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val samples: StateFlow<List<SampleEntity>> = range.flatMapLatest { c.monitoring.samples.between(from(it), Long.MAX_VALUE) }.state(this, emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val anomalies: StateFlow<List<AnomalyEntity>> = combine(range, activity) { r, a -> r to a }
        .flatMapLatest { (r, a) -> c.monitoring.anomalies.between(from(r), Long.MAX_VALUE, a) }.state(this, emptyList())
    val activities = c.monitoring.anomalies.activities().state(this, emptyList())
}

class AlertsViewModel(private val c: AppContainer) : ViewModel() {
    val alerts: StateFlow<List<AlertEntity>> = c.monitoring.alerts.recent(200).state(this, emptyList())
    fun acknowledge(id: Long) = viewModelScope.launch { c.monitoring.alerts.acknowledge(id) }
    fun acknowledgeAll() = viewModelScope.launch { c.monitoring.alerts.acknowledgeAll() }
}

class ResearchViewModel(private val c: AppContainer) : ViewModel() {
    val monitor: StateFlow<MonitoringState> = c.engine.state
    val probe: StateFlow<ResponsivenessProbe.State> = c.probe.state
    val workload: StateFlow<ControlledWorkload.State> = c.workload.state
    val events: StateFlow<List<SlowdownEventEntity>> = c.monitoring.slowdownEvents.recent(30).state(this, emptyList())
    val eventCount = c.monitoring.slowdownEvents.count().state(this, 0)
    val model: StateFlow<ModelStatus> = c.models.status
    val probeRef get() = c.probe
    val baseline: StateFlow<BaselineStats> = c.trainer.baselineStats().state(this, BaselineStats())
    val training: StateFlow<TrainingState> = c.trainer.state
    val policy: TrainingPolicy get() = c.trainer.policy
    fun trainNow(demo: Boolean = false) = c.trainer.trainNow(demo)

    fun setActivity(label: String?) = c.engine.setActivity(label)
    fun startProbe() = c.probe.startProbe()
    fun stopProbe() = c.probe.stopProbe()
    fun manualLag() = c.probe.recordManualLag()
    fun tapLatency(ms: Long) = c.probe.recordTapLatency(ms)
    fun startWorkload(k: ControlledWorkload.Kind) = c.workload.start(k)
    fun stopWorkload() = c.workload.stop()
    fun injectSimulation() = c.engine.injectSimulatedEpisode()
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.state(this, SettingsEntity())
    val model: StateFlow<ModelStatus> = c.models.status
    val monitor: StateFlow<MonitoringState> = c.engine.state
    val sampleCount = c.monitoring.samples.count().state(this, 0)
    val deviceInfo = c.monitoring.deviceInfo.observe().state(this, null)
    val message = MutableStateFlow<String?>(null)
    val shareIntent = MutableStateFlow<Intent?>(null)
    val baseline: StateFlow<BaselineStats> = c.trainer.baselineStats().state(this, BaselineStats())
    val training: StateFlow<TrainingState> = c.trainer.state
    val policy: TrainingPolicy get() = c.trainer.policy
    fun trainNow(demo: Boolean = false) = c.trainer.trainNow(demo)
    fun deletePersonalModel() = viewModelScope.launch {
        c.models.deletePersonal()
        message.value = "Personal model deleted. SmartFreeze AI is learning your phone again."
    }

    fun update(t: (SettingsEntity) -> SettingsEntity) = viewModelScope.launch {
        c.settings.update(t)
        val s = c.settings.current()
        c.engine.updatePolicy(s.cooldownMinutes, runCatching { Severity.valueOf(s.minAlertSeverity) }.getOrDefault(Severity.MEDIUM))
    }

    fun export() = viewModelScope.launch {
        message.value = "Preparing export…"
        message.value = try {
            val r = c.exporter.exportAll()
            shareIntent.value = c.exporter.shareIntent(r.files)
            "Exported ${r.sampleRows} samples. Choose where to save or send the files."
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }

    fun deleteAll() = viewModelScope.launch {
        c.engine.stop()
        c.monitoring.deleteAllMonitoringData()
        message.value = "All monitoring data deleted from this device."
    }

    fun applyRetentionNow() = viewModelScope.launch {
        val n = c.monitoring.applyRetention(c.settings.current().retentionDays, System.currentTimeMillis())
        message.value = "Removed $n old samples."
    }

    fun completeOnboarding() = update { it.copy(onboardingDone = true) }
}

object AppViewModelFactory {
    fun create(app: SmartFreezeApp): ViewModelProvider.Factory = viewModelFactory {
        initializer { MonitorViewModel(app.container) }
        initializer { HistoryViewModel(app.container) }
        initializer { AlertsViewModel(app.container) }
        initializer { ResearchViewModel(app.container) }
        initializer { SettingsViewModel(app.container) }
    }
}
