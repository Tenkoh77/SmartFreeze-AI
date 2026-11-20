package com.buse.smartfreeze

import android.content.Context
import com.buse.smartfreeze.data.collector.AndroidMetricsSource
import com.buse.smartfreeze.data.db.AppDatabase
import com.buse.smartfreeze.data.export.CsvExporter
import com.buse.smartfreeze.data.repository.ModelRepository
import com.buse.smartfreeze.data.repository.MonitoringRepository
import com.buse.smartfreeze.data.repository.SettingsRepository
import com.buse.smartfreeze.data.training.PersonalModelTrainer
import com.buse.smartfreeze.monitoring.AlertNotifier
import com.buse.smartfreeze.monitoring.MonitoringEngine
import com.buse.smartfreeze.research.ControlledWorkload
import com.buse.smartfreeze.research.ResponsivenessProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection container.
 *
 * Design decision: Hilt was considered (the brief allows it "where justified"). With about ten
 * long-lived objects, a single explicit container is easier to read, explain and defend, adds no
 * annotation processing or build-time generated code, and keeps the dependency graph visible in
 * one file. Every class still receives its dependencies through its constructor, so swapping to
 * Hilt later would only change this file.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { AppDatabase.build(app) }
    val settings by lazy { SettingsRepository(db.settings()) }
    val models by lazy { ModelRepository(app, db.modelMetadata()) }
    val monitoring by lazy { MonitoringRepository(db) }
    val notifier by lazy { AlertNotifier(app) }
    val exporter by lazy { CsvExporter(app, db) }
    val metrics by lazy { AndroidMetricsSource(app) }
    val trainer by lazy { PersonalModelTrainer(db, models, appScope) }
    val engine by lazy {
        MonitoringEngine(
            appScope, metrics, monitoring, models, settings, notifier,
            isWorkloadRunning = { workload.state.value.running != null },
            onSessionEnded = { trainer.requestCheck() },
        )
    }
    val workload by lazy { ControlledWorkload(appScope) }
    val probe by lazy { ResponsivenessProbe(appScope, monitoring) { engine.state.value.sessionId } }
}
