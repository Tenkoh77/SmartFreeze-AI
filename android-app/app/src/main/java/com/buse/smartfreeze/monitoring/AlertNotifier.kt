package com.buse.smartfreeze.monitoring

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.buse.smartfreeze.MainActivity
import com.buse.smartfreeze.R
import com.buse.smartfreeze.domain.model.Severity

class AlertNotifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_alerts_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITORING, context.getString(R.string.channel_monitoring_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_monitoring_desc) },
        )
    }

    /** True when Android currently allows us to post notifications (permission + user setting). */
    fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Returns true if the notification was posted. Permission is checked in canNotify(). */
    @SuppressLint("MissingPermission")
    fun notifyAlert(alertId: Long, title: String, text: String, severity: Severity): Boolean {
        if (!canNotify()) return false
        val open = PendingIntent.getActivity(
            context, alertId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "alerts")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_smartfreeze)
            .setContentTitle("$title (${severity.name.lowercase()})")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (severity == Severity.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIF_ALERT_BASE + (alertId % 1000).toInt(), n)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_MONITORING = "monitoring"
        const val NOTIF_MONITORING = 1
        private const val NOTIF_ALERT_BASE = 1000
    }
}
