package ai.wiro.realtime.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reports how long a realtime session has been running on a fixed
 * cadence.
 *
 * [start] replaces any previous run, so a restarted session cannot
 * leave an orphaned timer behind.
 */
class SessionElapsedClock(
    val tick: Duration = 1.seconds,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) {
    private val lock = Any()
    private var job: Job? = null

    val isRunning: Boolean
        get() = synchronized(lock) { job != null }

    fun start(onTick: (Duration) -> Unit) {
        stop()
        val started = scope.launch {
            var count = 0
            while (isActive) {
                delay(tick)
                if (!isActive) {
                    break
                }
                count += 1
                onTick(tick * count)
            }
        }
        synchronized(lock) { job = started }
    }

    fun stop() {
        val current = synchronized(lock) {
            val running = job
            job = null
            running
        }
        current?.cancel()
    }
}
