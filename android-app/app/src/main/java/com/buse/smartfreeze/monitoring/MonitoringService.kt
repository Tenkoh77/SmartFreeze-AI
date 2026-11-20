package com.buse.smartfreeze.monitoring

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.buse.smartfreeze.MainActivity
import com.buse.smartfreeze.R
import com.buse.smartfreeze.SmartFreezeApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service for an EXPLICITLY user-started, time-limited background monitoring session.
 * It shows an ongoing notification with a Stop action and stops itself after [MAX_SESSION_MS].
 * It is never started automatically, at boot, or to keep monitoring permanently active.
 */
class MonitoringService : LifecycleService() {

    private var timeout: Job? = null
    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val engine = (application as SmartFreezeApp).container.engine
        if (intent?.action == ACTION_STOP) {
            engine.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, AlertNotifier.NOTIF_MONITORING, buildNotification(), type)
        engine.start(MonitoringMode.BACKGROUND)
        // If the user stops monitoring from inside the app, end the service and its notification too.
        watcher?.cancel()
        watcher = lifecycleScope.launch {
            engine.state.dropWhile { !it.running }.first { !it.running }
            ServiceCompat.stopForeground(this@MonitoringService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        timeout?.cancel()
        timeout = lifecycleScope.launch {
            delay(MAX_SESSION_MS)
            engine.stop()
            ServiceCompat.stopForeground(this@MonitoringService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Not sticky: if Android kills the process, the session is NOT silently restarted.
        return START_NOT_STICKY
    }

    private fun buildNotification() = NotificationCompat.Builder(this, AlertNotifier.CHANNEL_MONITORING)
        .setSmallIcon(R.drawable.ic_stat_smartfreeze)
        .setContentTitle(getString(R.string.notif_monitoring_title))
        .setContentText(getString(R.string.notif_monitoring_text))
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, getString(R.string.notif_stop),
            PendingIntent.getService(this, 1, Intent(this, MonitoringService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val ACTION_STOP = "com.buse.smartfreeze.STOP_MONITORING"
        const val MAX_SESSION_MS = 2 * 60 * 60 * 1000L   // 2 hours

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
    }
}
