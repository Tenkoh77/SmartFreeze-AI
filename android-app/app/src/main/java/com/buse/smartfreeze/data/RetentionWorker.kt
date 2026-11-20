package com.buse.smartfreeze.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.buse.smartfreeze.SmartFreezeApp
import java.util.concurrent.TimeUnit

/** Daily deferrable cleanup of data older than the user's retention period (WorkManager). */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SmartFreezeApp).container
        return try {
            val days = c.settings.current().retentionDays
            val removed = c.monitoring.applyRetention(days, System.currentTimeMillis())
            Log.i("RetentionWorker", "removed $removed samples older than $days days")
            Result.success()
        } catch (e: Exception) {
            Log.e("RetentionWorker", "cleanup failed", e)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("retention-cleanup", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
