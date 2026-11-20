package com.buse.smartfreeze.domain

import com.buse.smartfreeze.domain.model.AlertCategory
import com.buse.smartfreeze.domain.model.Severity

/**
 * Decides whether a detection should become a user-visible alert/notification.
 *
 *  - Category NORMAL never alerts.
 *  - Severity below [minSeverity] (user preference) never alerts.
 *  - Suppression: after an alert of a category, further alerts of the same category are
 *    suppressed for [cooldownMs], UNLESS the severity escalates (LOW -> MEDIUM -> HIGH).
 *
 * Pure, deterministic and time-injected so it can be unit tested without Android.
 */
class AlertPolicy(
    var cooldownMs: Long = 10 * 60_000L,
    var minSeverity: Severity = Severity.MEDIUM,
) {
    private data class Last(val timeMs: Long, val severity: Severity)

    private val last = HashMap<AlertCategory, Last>()

    enum class Decision { ALERT, SUPPRESSED_COOLDOWN, BELOW_PREFERENCE, NOT_ALERTABLE }

    fun decide(category: AlertCategory, severity: Severity?, nowMs: Long): Decision {
        if (category == AlertCategory.NORMAL || severity == null) return Decision.NOT_ALERTABLE
        if (severity < minSeverity) return Decision.BELOW_PREFERENCE
        val prev = last[category]
        if (prev != null && nowMs - prev.timeMs < cooldownMs && severity <= prev.severity) {
            return Decision.SUPPRESSED_COOLDOWN
        }
        last[category] = Last(nowMs, severity)
        return Decision.ALERT
    }

    fun reset() = last.clear()
}
