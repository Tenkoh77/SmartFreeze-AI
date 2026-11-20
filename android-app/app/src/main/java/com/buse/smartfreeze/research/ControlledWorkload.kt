package com.buse.smartfreeze.research

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * RESEARCH-ONLY controlled workloads that act on SmartFreeze AI's OWN process, to create
 * reproducible "unusual condition" episodes (Scenario C). They never touch other apps, are
 * bounded in size and duration, and stop immediately when cancelled.
 *
 *  - MEMORY: holds Java heap allocations up to ~60 % of this app's free heap.
 *  - CPU:    busy arithmetic on (cores - 1) background threads (raises temperature gradually).
 *  - JANK:   blocks THIS app's main thread for 300 ms every 2 s, a labelled slowdown for the probe.
 */
class ControlledWorkload(private val scope: CoroutineScope) {
    enum class Kind(val label: String) { MEMORY("Memory pressure"), CPU("CPU load"), JANK("UI jank (this app only)") }

    data class State(val running: Kind? = null, val startedAtMs: Long? = null, val detail: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private val held = ArrayList<ByteArray>()

    fun start(kind: Kind, durationS: Int = 120) {
        stop()
        val d = durationS.coerceIn(10, 600)
        _state.value = State(kind, System.currentTimeMillis(), "starting")
        job = scope.launch(Dispatchers.Default) {
            try {
                when (kind) {
                    Kind.MEMORY -> memory(d)
                    Kind.CPU -> cpu(d)
                    Kind.JANK -> jank(d)
                }
            } finally {
                held.clear()
                _state.value = State()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        held.clear()
        _state.value = State()
    }

    private suspend fun memory(durationS: Int) {
        val rt = Runtime.getRuntime()
        val budget = ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) * 0.6).toLong()
        val chunk = 8 * 1024 * 1024
        val end = SystemClock.elapsedRealtime() + durationS * 1000L
        var allocated = 0L
        while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < end) {
            if (allocated + chunk < budget) {
                held += ByteArray(chunk).also { it.fill(1) }    // touch pages so they are really committed
                allocated += chunk
                _state.value = _state.value.copy(detail = "holding ${allocated / (1024 * 1024)} MB")
            }
            delay(1_000)
        }
    }

    private suspend fun cpu(durationS: Int) {
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        val end = SystemClock.elapsedRealtime() + durationS * 1000L
        _state.value = _state.value.copy(detail = "$threads busy threads")
        val workers = List(threads) {
            scope.launch(Dispatchers.Default) {
                var x = 1.0
                while (isActive && SystemClock.elapsedRealtime() < end) {
                    repeat(200_000) { x = sqrt(x + it) }
                    if (x < 0) println(x)   // prevents the loop from being optimised away
                }
            }
        }
        workers.forEach { it.join() }
    }

    private suspend fun jank(durationS: Int) {
        val main = Handler(Looper.getMainLooper())
        val end = SystemClock.elapsedRealtime() + durationS * 1000L
        var stalls = 0
        while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < end) {
            main.post { SystemClock.sleep(300) }    // deliberate, bounded stall of OUR main thread only
            stalls++
            _state.value = _state.value.copy(detail = "$stalls stalls of 300 ms")
            delay(2_000)
        }
    }
}
