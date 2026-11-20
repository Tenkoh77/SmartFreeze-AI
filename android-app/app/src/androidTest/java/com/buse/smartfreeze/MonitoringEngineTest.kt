package com.buse.smartfreeze

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buse.smartfreeze.data.collector.DeviceReading
import com.buse.smartfreeze.data.collector.MetricsSource
import com.buse.smartfreeze.data.db.AppDatabase
import com.buse.smartfreeze.data.repository.ModelRepository
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.data.repository.MonitoringRepository
import com.buse.smartfreeze.data.repository.SettingsRepository
import com.buse.smartfreeze.monitoring.AlertNotifier
import com.buse.smartfreeze.monitoring.MonitoringEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end engine test with SIMULATED metrics (dependency inversion via MetricsSource). */
@RunWith(AndroidJUnit4::class)
class MonitoringEngineTest {

    private class FakeSource : MetricsSource {
        var t = 1_700_000_000_000L
        override fun read(): DeviceReading {
            t += 5_000
            return DeviceReading(t, 8_000_000_000, 3_000_000_000, 500_000_000, false, 0, null, 80, false, 30.0, 2,
                100, 50, null, null)
        }
    }

    @Test
    fun startCollectsSamplesAndRunsModelThenStops() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(db.settings())
        settings.update { it.copy(intervalS = 5, notificationsEnabled = false) }
        val models = ModelRepository(ctx, db.modelMetadata())
        models.load()
        val engine = MonitoringEngine(scope, FakeSource(), MonitoringRepository(db), models, settings, AlertNotifier(ctx))

        engine.start()
        withTimeout(20_000) { while (engine.state.value.samplesTaken < 2) delay(100) }
        val st = engine.state.value
        assertTrue(st.running)
        if (models.status.value is ModelStatus.Ready) assertTrue("model produced a score", st.lastResult?.score != null)
        engine.stop()
        withTimeout(5_000) { while (engine.state.value.running) delay(50) }
        assertTrue(db.samples().count().first() >= 2)
        scope.cancel()
        db.close()
    }
}
