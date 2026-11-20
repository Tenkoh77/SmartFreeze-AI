package com.buse.smartfreeze.data.training

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.buse.smartfreeze.SmartFreezeApp
import com.buse.smartfreeze.data.repository.ModelStatus
import java.util.concurrent.TimeUnit

/** Daily deferrable check: train the personal model when the baseline is complete, or retrain weekly. */
class ModelTrainingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SmartFreezeApp).container
        if (c.models.status.value == ModelStatus.Loading) c.models.load()
        c.trainer.trainIfDue()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ModelTrainingWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("personal-model-training", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
