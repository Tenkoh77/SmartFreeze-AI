package com.buse.smartfreeze.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(b: Long?): String {
        if (b == null) return "—"
        val gb = b / 1_073_741_824.0
        return if (gb >= 1) "%.1f GB".format(gb) else "%.0f MB".format(b / 1_048_576.0)
    }

    fun pct(fraction: Double?): String = if (fraction == null || fraction.isNaN()) "—" else "%.0f%%".format(fraction * 100)

    fun score(v: Double?): String = if (v == null || v.isNaN()) "—" else "%.3f".format(v)

    fun thermal(status: Int?): String = when (status) {
        null -> "Not available"
        0 -> "None"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        6 -> "Shutdown"
        else -> "Unknown ($status)"
    }

    fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
    fun dateTime(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%dh %02dm".format(s / 3600, (s % 3600) / 60) else "%dm %02ds".format(s / 60, s % 60)
    }

    /** Plain-language names for model features (used in the AI Insights screen). */
    fun featureLabel(name: String): String = when (name) {
        "mem_avail_ratio" -> "Available memory"
        "mem_margin_ratio" -> "Memory above Android's low-memory line"
        "low_memory" -> "Low-memory flag"
        "thermal_status" -> "Thermal status"
        "thermal_headroom" -> "Thermal headroom"
        "battery_temp_c" -> "Battery temperature"
        "storage_free_ratio" -> "Free storage"
        "mem_ratio_std_w" -> "Memory fluctuation (window)"
        "mem_ratio_slope_w" -> "Memory trend per minute"
        "thermal_delta_w" -> "Thermal rise (window)"
        "pressure_fraction_w" -> "Time under resource pressure"
        else -> name
    }

    val ACTIVITIES = listOf(
        "idle" to "Idle", "browsing" to "Browsing", "video" to "Video", "app_switching" to "App switching",
        "charging" to "Charging", "gaming" to "Gaming", "daily_use" to "Daily use", "stress_test" to "Stress test",
    )

    fun activity(key: String?): String = ACTIVITIES.firstOrNull { it.first == key }?.second ?: "Not labelled"
}
