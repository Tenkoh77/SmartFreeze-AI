package com.buse.smartfreeze

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buse.smartfreeze.data.collector.DeviceReading
import com.buse.smartfreeze.data.db.AppDatabase
import com.buse.smartfreeze.data.repository.MonitoringRepository
import com.buse.smartfreeze.data.repository.SettingsRepository
import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.DetectionResult
import com.buse.smartfreeze.domain.model.DetectionSource
import com.buse.smartfreeze.domain.model.Severity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented (on-device / emulator) tests for Room persistence. */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MonitoringRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = MonitoringRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun reading(t: Long, thermal: Int? = 1) = DeviceReading(
        wallTimeMs = t, memTotalBytes = 8_000_000_000, memAvailBytes = 3_000_000_000, memThresholdBytes = 500_000_000,
        lowMemory = false, thermalStatus = thermal, thermalHeadroom = null, batteryLevelPct = 70, isCharging = false,
        batteryTempC = 31.5, batteryHealth = 2, storageTotalBytes = 100, storageAvailBytes = 40, appPssKb = 60_000, appCpuMs = 10,
    )

    @Test
    fun duplicateSamplesAreIgnoredAndMissingStaysNull() = runTest {
        val s = repo.startSession(10, "foreground", false, 1_000)
        val first = repo.insertSample(s, 10, reading(2_000, thermal = null), "idle")
        val dup = repo.insertSample(s, 10, reading(2_000), "idle")
        assertEquals(null, dup)
        val rows = db.samples().forSession(s)
        assertEquals(1, rows.size)
        assertEquals(first, rows[0].id)
        assertNull(rows[0].thermalStatus)                 // stored as missing, not 0
    }

    @Test
    fun deletingSessionCascadesAndRetentionWorks() = runTest {
        val s = repo.startSession(10, "foreground", false, 1_000)
        val sample = repo.insertSample(s, 10, reading(2_000), null)
        val r = DetectionResult(2_000, DetectionSource.ISOLATION_FOREST, "general", "v", 0.7, 0.6, true,
            AlertCategory.UNUSUAL_CONDITION, Severity.MEDIUM, emptyList(), "x", listOf("y"))
        repo.saveDetection(s, sample, 60, DoubleArray(16) { 0.5 }, r, null)
        repo.endSession(s, 3_000)
        assertEquals(1, db.anomalies().latest().first()?.let { 1 } ?: 0)
        repo.applyRetention(days = 1, now = 2 * 86_400_000L + 5_000)
        assertEquals(0, db.samples().forSession(s).size)
        assertNull(db.anomalies().latest().first())
    }

    @Test
    fun settingsAreClampedAndPersisted() = runTest {
        val settings = SettingsRepository(db.settings())
        settings.update { it.copy(intervalS = 1, retentionDays = 9999) }
        val s = settings.current()
        assertEquals(SettingsRepository.MIN_INTERVAL_S, s.intervalS)
        assertEquals(365, s.retentionDays)
    }
}
