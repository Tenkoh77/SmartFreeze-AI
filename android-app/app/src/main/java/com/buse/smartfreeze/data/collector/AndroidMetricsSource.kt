package com.buse.smartfreeze.data.collector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log

/**
 * Reads metrics through PUBLIC Android APIs only (no root, no privileged permissions).
 *
 * What we CAN observe (Android 11+):
 *  - ActivityManager.MemoryInfo: totalMem, availMem, threshold, lowMemory (system-wide).
 *    availMem is memory the system considers available (free + reclaimable caches), which is
 *    NOT the same as physically unused RAM.
 *  - PowerManager.getCurrentThermalStatus() (API 29+) and getThermalHeadroom() (API 30+).
 *    Headroom returns NaN when unsupported or called more often than about once per second;
 *    we store null in that case.
 *  - Sticky ACTION_BATTERY_CHANGED broadcast: level, status/plug state, temperature, health.
 *  - StatFs on the app's data partition: total and available bytes of /data.
 *  - Our OWN process: PSS via Debug.getMemoryInfo, CPU time via Process.getElapsedCpuTime().
 *
 * What we CANNOT observe (documented limitations): system-wide CPU utilisation (/proc/stat is
 * restricted since Android 8), other apps' memory or CPU, other processes' lists, system logs,
 * and the real frame rate of other apps.
 */
class AndroidMetricsSource(private val context: Context) : MetricsSource {

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val memInfo = ActivityManager.MemoryInfo()
    private val debugMem = Debug.MemoryInfo()
    private var lastHeadroomCallMs = 0L

    override fun read(): DeviceReading {
        activityManager.getMemoryInfo(memInfo)
        val battery: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: RuntimeException) {
            Log.w(TAG, "battery broadcast unavailable", e); null
        }
        val st = storage()
        return DeviceReading(
            wallTimeMs = System.currentTimeMillis(),
            memTotalBytes = memInfo.totalMem,
            memAvailBytes = memInfo.availMem.coerceIn(0L, memInfo.totalMem),
            memThresholdBytes = memInfo.threshold,
            lowMemory = memInfo.lowMemory,
            thermalStatus = safe { powerManager.currentThermalStatus },
            thermalHeadroom = readHeadroom(),
            batteryLevelPct = battery?.let { batteryPct(it) } ?: batteryPctFallback(),
            isCharging = battery?.let { charging(it) },
            batteryTempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE && it in -200..800 }?.let { it / 10.0 },
            batteryHealth = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it > 0 },
            storageTotalBytes = st?.first,
            storageAvailBytes = st?.second,
            appPssKb = safe { Debug.getMemoryInfo(debugMem); debugMem.totalPss.toLong() }?.takeIf { it > 0 },
            appCpuMs = safe { Process.getElapsedCpuTime() },
        )
    }

    private fun readHeadroom(): Double? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadroomCallMs < MIN_HEADROOM_INTERVAL_MS) return null   // respect rate limit
        lastHeadroomCallMs = now
        val h = safe { powerManager.getThermalHeadroom(HEADROOM_FORECAST_S) } ?: return null
        return if (h.isNaN() || h < 0f) null else h.toDouble()
    }

    private fun batteryPct(i: Intent): Int? {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
    }

    private fun batteryPctFallback(): Int? = safe {
        context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }?.takeIf { it in 0..100 }

    private fun charging(i: Intent): Boolean {
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0)
    }

    private fun storage(): Pair<Long, Long>? = safe {
        val s = StatFs(context.filesDir.path)
        s.totalBytes to s.availableBytes
    }?.takeIf { it.first > 0 }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "metric unavailable: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "AndroidMetricsSource"
        private const val HEADROOM_FORECAST_S = 10
        private const val MIN_HEADROOM_INTERVAL_MS = 1_500L
    }
}
