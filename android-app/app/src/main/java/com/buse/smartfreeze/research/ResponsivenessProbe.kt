package com.buse.smartfreeze.research

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import com.buse.smartfreeze.data.repository.MonitoringRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * RESEARCH-ONLY ground-truth collection: measures the responsiveness of SmartFreeze AI's OWN
 * user interface. It cannot and does not measure other apps.
 *
 * Slowdown event definition (must match machine-learning/.../research/slowdown.py):
 *   (a) latency_probe: a main-thread probe scheduled every second runs > 250 ms late;
 *   (b) frame: a rendered frame of the research screen takes > 250 ms (Android FrameMetrics);
 *   (c) tap_latency: tap-to-next-frame time on the research tap test > 250 ms;
 *   (d) manual: the participant presses "I noticed lag".
 * The Python pipeline merges events closer than 30 s.
 */
class ResponsivenessProbe(
    private val scope: CoroutineScope,
    private val repo: MonitoringRepository,
    private val sessionId: () -> Long?,
) {
    data class State(
        val probing: Boolean = false,
        val probesRun: Int = 0,
        val maxLatenessMs: Long = 0,
        val lastLatenessMs: Long = 0,
        val eventsRecorded: Int = 0,
        val slowFrames: Int = 0,
        val lastTapLatencyMs: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var frameThread: HandlerThread? = null
    private var attachedWindow: Window? = null
    private var lastEventAt = 0L

    private val probeRunnable = object : Runnable {
        var scheduledAt = 0L
        override fun run() {
            val lateness = SystemClock.uptimeMillis() - scheduledAt
            _state.update { it.copy(probesRun = it.probesRun + 1, lastLatenessMs = lateness, maxLatenessMs = maxOf(it.maxLatenessMs, lateness)) }
            if (lateness > SLOW_MS) record("latency_probe", lateness)
            if (_state.value.probing) schedule()
        }

        fun schedule() {
            scheduledAt = SystemClock.uptimeMillis() + PROBE_PERIOD_MS
            main.postAtTime(this, scheduledAt)
        }
    }

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
        if (totalMs > SLOW_MS) {
            _state.update { it.copy(slowFrames = it.slowFrames + 1) }
            record("frame", totalMs)
        }
    }

    fun startProbe() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true) }
        probeRunnable.schedule()
    }

    fun stopProbe() {
        _state.update { it.copy(probing = false) }
        main.removeCallbacks(probeRunnable)
    }

    /** Attach FrameMetrics to the research screen's window (API 24+). */
    fun attachFrameMetrics(window: Window) {
        if (attachedWindow === window) return
        detachFrameMetrics()
        val t = HandlerThread("frame-metrics").also { it.start() }
        frameThread = t
        window.addOnFrameMetricsAvailableListener(frameListener, Handler(t.looper))
        attachedWindow = window
    }

    fun detachFrameMetrics() {
        attachedWindow?.let { runCatching { it.removeOnFrameMetricsAvailableListener(frameListener) } }
        attachedWindow = null
        frameThread?.quitSafely()
        frameThread = null
    }

    fun recordTapLatency(ms: Long) {
        _state.update { it.copy(lastTapLatencyMs = ms) }
        if (ms > SLOW_MS) record("tap_latency", ms)
    }

    fun recordManualLag(note: String? = null) = record("manual", null, source = "user", note = note, force = true)

    private fun record(type: String, latencyMs: Long?, source: String = "research_probe", note: String? = null, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Debounce automatic events (1 s) so one long stall does not create dozens of rows.
        if (!force && now - lastEventAt < 1_000) return
        lastEventAt = now
        _state.update { it.copy(eventsRecorded = it.eventsRecorded + 1) }
        scope.launch { repo.recordSlowdown(sessionId(), now, type, latencyMs, source, note) }
    }

    companion object {
        const val SLOW_MS = 250L
        private const val PROBE_PERIOD_MS = 1_000L

        /** This app's own recent exits (ANR, crash, low-memory kill) as reported by Android 11+. */
        fun ownExitReasons(context: Context, max: Int = 10): List<ApplicationExitInfo> =
            runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, max)
            }.getOrDefault(emptyList())

        fun exitReasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
            ApplicationExitInfo.REASON_CRASH -> "Crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed for low memory"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "Stopped by user"
            ApplicationExitInfo.REASON_EXIT_SELF -> "Exited normally"
            ApplicationExitInfo.REASON_SIGNALED -> "Signalled"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
            else -> "Other ($reason)"
        }
    }
}
