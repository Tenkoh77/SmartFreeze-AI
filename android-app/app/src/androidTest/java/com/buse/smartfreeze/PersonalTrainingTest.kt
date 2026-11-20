package com.buse.smartfreeze

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buse.smartfreeze.data.collector.DeviceReading
import com.buse.smartfreeze.data.db.AppDatabase
import com.buse.smartfreeze.data.repository.ModelRepository
import com.buse.smartfreeze.data.repository.ModelStatus
import com.buse.smartfreeze.data.repository.MonitoringRepository
import com.buse.smartfreeze.data.training.PersonalModelTrainer
import com.buse.smartfreeze.domain.TrainingPolicy
import com.buse.smartfreeze.ml.ModelBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * End-to-end ON-DEVICE training on a real phone/emulator: samples in Room -> PersonalModelTrainer ->
 * personal_model.json -> ModelRepository loads it as the active model. Uses a relaxed TrainingPolicy
 * so the test needs minutes of simulated data, not days. Also measures training time on the device.
 */
@RunWith(AndroidJUnit4::class)
class PersonalTrainingTest {
    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        File(ctx.filesDir, "model/${ModelRepository.PERSONAL_FILE}").delete()
    }

    @After fun tearDown() {
        scope.cancel(); db.close()
        File(ctx.filesDir, "model/${ModelRepository.PERSONAL_FILE}").delete()
    }

    @Test
    fun trainsPersonalModelFromStoredSamplesAndActivatesIt() = runBlocking {
        val repo = MonitoringRepository(db)
        val rng = Random(1)
        val day = 86_400_000L
        val start = System.currentTimeMillis() - 3 * day
        for (d in 0 until 3) {
            val sid = repo.startSession(10, "foreground", false, start + d * day)
            var ratio = 0.4
            for (i in 0 until 400) {
                ratio = (0.85 * ratio + 0.15 * 0.4 + (rng.nextDouble() - 0.5) * 0.04).coerceIn(0.05, 0.9)
                repo.insertSample(sid, 10, DeviceReading(
                    start + d * day + i * 10_000L, 8_000_000_000, (ratio * 8_000_000_000).toLong(), 500_000_000, false,
                    0, null, 70, false, 31.0, 2, 100, 40, null, null), "idle")
            }
            // stress-test samples must be ignored by training
            repo.insertSample(sid, 10, DeviceReading(start + d * day + 9_000_000, 8_000_000_000, 10_000_000, 500_000_000,
                true, 4, null, 70, false, 45.0, 2, 100, 40, null, null), "stress_test", excludeFromTraining = true)
        }
        val stats = db.samples().baselineStats(0)
        assertEquals(1200, stats.samples)
        assertTrue(stats.days >= 3)   // sessions may cross midnight

        val models = ModelRepository(ctx, db.modelMetadata()).also { it.load() }
        val policy = TrainingPolicy(minHours = 3.0, minSamples = 1000, minDays = 3)
        val trainer = PersonalModelTrainer(db, models, scope, policy)
        trainer.trainIfDue()

        val st = models.status.value
        assertTrue("expected a trained model, got $st / ${trainer.state.value.message}", st is ModelStatus.Ready)
        val b = (st as ModelStatus.Ready).bundle
        assertEquals(ModelBundle.ORIGIN_ON_DEVICE, b.origin)
        assertEquals(1200, (b.metadata["training_samples"] as Number).toInt())
        println("On-device training time on this device: ${b.metadata["total_training_ms"]} ms")

        // A fresh repository (as after an app restart) loads the saved personal model.
        val reloaded = ModelRepository(ctx, db.modelMetadata()).also { it.load() }
        assertEquals(b.modelVersion, (reloaded.status.value as ModelStatus.Ready).bundle.modelVersion)
    }
}
