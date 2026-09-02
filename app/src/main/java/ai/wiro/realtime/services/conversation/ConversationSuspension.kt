package ai.wiro.realtime.services.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Wall-clock hold for a conversation the speaker left the screen of.
 *
 * The timer hangs the task up if nobody comes back. A frozen app runs
 * no timers, so [hasLapsed] is the source of truth on resume.
 */
class ConversationSuspension(
    val gracePeriod: Duration,
    private val onExpired: () -> Unit,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var expireJob: Job? = null
    private var suspendedAt: Long? = null
    private var disposed = false

    /** A configured zero (or less) means leaving is hanging up. */
    val holdsAnything: Boolean
        get() = gracePeriod > Duration.ZERO

    /**
     * True when no hold is running, or wall-clock time has passed the
     * grace period — even if the timer never fired.
     */
    val hasLapsed: Boolean
        get() = synchronized(lock) {
            val started = suspendedAt ?: return@synchronized true
            nowMillis() - started > gracePeriod.inWholeMilliseconds
        }

    fun begin() {
        synchronized(lock) {
            if (disposed) {
                return
            }
            expireJob?.cancel()
            suspendedAt = nowMillis()
            val grace = gracePeriod
            expireJob = scope.launch {
                delay(grace)
                expire()
            }
        }
    }

    /** Cancels the timer without forgetting when the hold started. */
    fun cancel() {
        synchronized(lock) {
            expireJob?.cancel()
            expireJob = null
        }
    }

    /** Drops the hold entirely: the session is ending or coming back. */
    fun clear() {
        synchronized(lock) {
            expireJob?.cancel()
            expireJob = null
            suspendedAt = null
        }
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            expireJob?.cancel()
            expireJob = null
            suspendedAt = null
        }
    }

    private fun expire() {
        val shouldNotify = synchronized(lock) {
            expireJob = null
            !disposed
        }
        if (shouldNotify) {
            onExpired()
        }
    }
}
