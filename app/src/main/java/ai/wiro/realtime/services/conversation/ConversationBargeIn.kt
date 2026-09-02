package ai.wiro.realtime.services.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Two-stage barge-in: a loud moment silences the assistant while
 * keeping its audio; only input that sustains like speech commits
 * the cut, while a noise burst hands the audio back from where it
 * stopped.
 */
class ConversationBargeIn(
    private val playback: ConversationPlaybackCoordinator,
    private val voiceActivity: ConversationVoiceActivity,
    private val onProtocolEvent: (String) -> Unit,
    private val scope: CoroutineScope,
    private val holdTimeout: Duration = 900.milliseconds,
    private val holdCooldown: Duration = 1_500.milliseconds,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var holdJob: Job? = null
    private var holdSuppressedUntil = 0L
    private var disposed = false

    /**
     * Called by the service after every microphone chunk it accepted.
     */
    fun evaluate() {
        synchronized(lock) {
            if (disposed) {
                return
            }
            if (playback.isHolding) {
                when {
                    voiceActivity.userInterrupting -> commitInterruption()
                    !voiceActivity.hasInterruptEvidence -> resumeHeldTurn()
                }
                return
            }
            if (voiceActivity.userInterrupting) {
                commitInterruption()
                return
            }
            if (
                voiceActivity.userMaybeInterrupting &&
                nowMillis() >= holdSuppressedUntil &&
                playback.holdTurn()
            ) {
                onProtocolEvent("assistant_playback_held")
                startHoldDeadline()
            }
        }
    }

    fun cancelHold() {
        synchronized(lock) {
            holdJob?.cancel()
            holdJob = null
        }
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            holdJob?.cancel()
            holdJob = null
        }
    }

    private fun commitInterruption() {
        if (!playback.interruptTurn()) {
            return
        }
        cancelHoldUnlocked()
        voiceActivity.resetAi()
        onProtocolEvent("assistant_interrupted")
    }

    private fun resumeHeldTurn() {
        cancelHoldUnlocked()
        val resumed = playback.resumeHeldTurn()
        voiceActivity.resetAi()
        resumed.forEach { voiceActivity.addAiChunk(it) }
        holdSuppressedUntil = nowMillis() + holdCooldown.inWholeMilliseconds
        onProtocolEvent("assistant_playback_resumed")
    }

    private fun startHoldDeadline() {
        holdJob?.cancel()
        val timeout = holdTimeout
        holdJob = scope.launch {
            delay(timeout)
            synchronized(lock) {
                holdJob = null
                if (!disposed && playback.isHolding) {
                    resumeHeldTurn()
                }
            }
        }
    }

    private fun cancelHoldUnlocked() {
        holdJob?.cancel()
        holdJob = null
    }
}
