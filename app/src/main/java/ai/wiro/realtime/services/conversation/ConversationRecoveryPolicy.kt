package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.audio.AudioFailure
import ai.wiro.realtime.networking.WiroFailure
import ai.wiro.realtime.networking.WiroFailureCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Retry and auto-restart budgets for a conversation that lost its
 * task.
 *
 * A healthy microphone chunk reaching the server is the only signal
 * that both counters should refill.
 */
class ConversationRecoveryPolicy(
    private val scope: CoroutineScope,
    val maxRetryAttempts: Int = 5,
    val retryBaseDelay: Duration = 500.milliseconds,
    val autoRestartDelay: Duration = 5.seconds,
    val maxAutoRestarts: Int = 2,
) {
    private val lock = Any()
    private var autoRestartJob: Job? = null
    var retryAttempt: Int = 0
        private set
    private var autoRestartCycle = 0
    private var disposed = false

    /**
     * Backoff after the current retry was scheduled: doubles each
     * time.
     */
    val nextRetryDelay: Duration
        get() {
            val shift = max(0, retryAttempt - 1)
            return retryBaseDelay * (1 shl shift)
        }

    fun shouldRetry(error: Throwable, isReconfiguring: Boolean): Boolean {
        if (isReconfiguring || retryAttempt >= maxRetryAttempts) {
            return false
        }
        return isRecoverable(error)
    }

    fun isRecoverable(error: Throwable): Boolean {
        if (error is AudioFailure) {
            return true
        }
        val failure = error as? WiroFailure ?: return false
        return when (failure.code) {
            WiroFailureCode.ServerUnavailable,
            WiroFailureCode.NetworkUnavailable,
            WiroFailureCode.RequestTimeout,
            WiroFailureCode.SocketConnection,
            WiroFailureCode.SocketConnectTimeout,
            WiroFailureCode.StreamReadyTimeout,
            -> true

            WiroFailureCode.Authentication,
            WiroFailureCode.ApiRejected,
            WiroFailureCode.RateLimited,
            WiroFailureCode.Cancelled,
            WiroFailureCode.MalformedResponse,
            WiroFailureCode.RealtimeProtocol,
            WiroFailureCode.SessionBusy,
            -> false
        }
    }

    fun noteRetryScheduled() {
        synchronized(lock) { retryAttempt += 1 }
    }

    /** A microphone chunk reached the server, so both budgets refill. */
    fun noteHealthy() {
        synchronized(lock) {
            retryAttempt = 0
            autoRestartCycle = 0
        }
    }

    /** A user (or auto-restart) start; in-flight retries reset. */
    fun resetRetries() {
        synchronized(lock) { retryAttempt = 0 }
    }

    /** Arms a restart. Returns false when the budget is spent. */
    fun scheduleAutoRestart(restart: () -> Unit): Boolean {
        synchronized(lock) {
            if (disposed || autoRestartCycle >= maxAutoRestarts) {
                return false
            }
            autoRestartJob?.cancel()
            val shift = if (autoRestartCycle > 2) 2 else autoRestartCycle
            autoRestartCycle += 1
            val wait = autoRestartDelay * (1 shl shift)
            autoRestartJob = scope.launch {
                delay(wait)
                val live = synchronized(lock) { !disposed }
                if (live) {
                    restart()
                }
            }
            return true
        }
    }

    fun cancelAutoRestart() {
        synchronized(lock) {
            autoRestartJob?.cancel()
            autoRestartJob = null
        }
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            autoRestartJob?.cancel()
            autoRestartJob = null
        }
    }
}
