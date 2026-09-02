package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.audio.AudioConstants
import ai.wiro.realtime.audio.pcmNormalizedLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class ConversationVoicePhase {
    Quiet,
    UserSpeaking,
    AiSpeaking,
}

/**
 * Live voice signals for barge-in.
 *
 * [phase] is discrete. [userLevel] is continuous and silent: it is
 * meant to be polled, so fifty audio chunks a second never schedule a
 * rebuild on their own.
 *
 * Assistant audio arrives from the network far faster than it plays.
 * Every enqueued chunk extends a playhead by its own duration, and the
 * assistant counts as speaking while the playhead is in the future.
 */
class ConversationVoiceActivity(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val aiSegments = mutableListOf<AiAudioSegment>()
    private var userLevelValue = 0.0
    private var interruptStreak = 0
    private var userActiveUntil = EPOCH
    private var aiPlayheadUntil = EPOCH
    private var currentPhase = ConversationVoicePhase.Quiet
    private var phaseJob: Job? = null
    private var disposed = false

    val phase: ConversationVoicePhase
        get() = synchronized(lock) { currentPhase }

    val userLevel: Double
        get() = synchronized(lock) { userLevelValue }

    /**
     * True while queued assistant audio is still playing through the
     * speaker, which is exactly when microphone input may carry echo.
     */
    val aiAudioPending: Boolean
        get() = synchronized(lock) { nowMillis() < aiPlayheadUntil }

    /**
     * True while the user has been loud over pending assistant audio
     * just long enough to be either speech or a burst of noise.
     */
    val userMaybeInterrupting: Boolean
        get() = synchronized(lock) {
            interruptStreak >= SUSPECT_STREAK_TARGET &&
                nowMillis() < aiPlayheadUntil
        }

    /**
     * True once loud input over pending assistant audio has sustained
     * the way only speech does.
     */
    val userInterrupting: Boolean
        get() = synchronized(lock) {
            interruptStreak >= CONFIRM_STREAK_TARGET &&
                nowMillis() < aiPlayheadUntil
        }

    val hasInterruptEvidence: Boolean
        get() = synchronized(lock) { interruptStreak > 0 }

    val aiLevel: Double
        get() = synchronized(lock) {
            val now = nowMillis()
            pruneExpiredSegments(now)
            val head = aiSegments.firstOrNull() ?: return@synchronized 0.0
            if (head.start > now) 0.0 else head.level
        }

    fun addUserChunk(chunk: ByteArray, level: Double? = null) {
        synchronized(lock) {
            if (disposed) {
                return
            }
            val measured = level ?: pcmNormalizedLevel(chunk)
            val smoothing = if (measured > userLevelValue) 0.5 else 0.2
            userLevelValue += (measured - userLevelValue) * smoothing
            val now = nowMillis()
            val aiPending = now < aiPlayheadUntil
            if (!aiPending) {
                interruptStreak = 0
            } else if (measured >= INTERRUPT_THRESHOLD) {
                if (interruptStreak < CONFIRM_STREAK_TARGET) {
                    interruptStreak += 1
                }
            } else if (
                measured < INTERRUPT_SUSTAIN_THRESHOLD &&
                interruptStreak > 0
            ) {
                interruptStreak -= 1
            }
            val threshold = if (aiPending) {
                INTERRUPT_THRESHOLD
            } else {
                SPEECH_THRESHOLD
            }
            if (measured >= threshold) {
                userActiveUntil = now + USER_HANGOVER.inWholeMilliseconds
            }
            updatePhase(now)
        }
    }

    fun addAiChunk(chunk: ByteArray) {
        synchronized(lock) {
            if (disposed) {
                return
            }
            val now = nowMillis()
            pruneExpiredSegments(now)
            val start = if (aiPlayheadUntil > now) aiPlayheadUntil else now
            val end = start + chunkDurationMillis(chunk)
            aiSegments += AiAudioSegment(
                start = start,
                end = end,
                level = pcmNormalizedLevel(chunk),
            )
            aiPlayheadUntil = end
            updatePhase(now)
        }
    }

    fun resetAi() {
        synchronized(lock) {
            if (disposed) {
                return
            }
            aiSegments.clear()
            aiPlayheadUntil = EPOCH
            updatePhase(nowMillis())
        }
    }

    fun resetUser() {
        synchronized(lock) {
            if (disposed) {
                return
            }
            userLevelValue = 0.0
            interruptStreak = 0
            userActiveUntil = EPOCH
            updatePhase(nowMillis())
        }
    }

    fun reset() {
        synchronized(lock) {
            if (disposed) {
                return
            }
            aiSegments.clear()
            aiPlayheadUntil = EPOCH
            userLevelValue = 0.0
            interruptStreak = 0
            userActiveUntil = EPOCH
            updatePhase(nowMillis())
        }
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            phaseJob?.cancel()
            phaseJob = null
        }
    }

    /**
     * The user keeps priority: interrupting the assistant should feel
     * heard immediately.
     */
    private fun updatePhase(now: Long) {
        val next = when {
            now < userActiveUntil -> ConversationVoicePhase.UserSpeaking
            now < aiPlayheadUntil -> ConversationVoicePhase.AiSpeaking
            else -> ConversationVoicePhase.Quiet
        }
        schedulePhaseCheck(now)
        if (next == currentPhase) {
            return
        }
        currentPhase = next
    }

    private fun schedulePhaseCheck(now: Long) {
        phaseJob?.cancel()
        phaseJob = null
        val nearest = listOf(userActiveUntil, aiPlayheadUntil)
            .filter { it > now }
            .minOrNull() ?: return
        val delayMs = (nearest - now) + 20
        phaseJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                if (!disposed) {
                    updatePhase(nowMillis())
                }
            }
        }
    }

    private fun pruneExpiredSegments(now: Long) {
        while (aiSegments.isNotEmpty() && aiSegments.first().end <= now) {
            aiSegments.removeAt(0)
        }
    }

    private fun chunkDurationMillis(chunk: ByteArray): Long = chunk.size * 1000L / BYTES_PER_SECOND

    private data class AiAudioSegment(
        val start: Long,
        val end: Long,
        val level: Double,
    )

    private companion object {
        const val EPOCH: Long = 0L
        const val SPEECH_THRESHOLD: Double = 0.18
        const val INTERRUPT_THRESHOLD: Double = 0.4
        const val INTERRUPT_SUSTAIN_THRESHOLD: Double = 0.25
        const val SUSPECT_STREAK_TARGET: Int = 3
        const val CONFIRM_STREAK_TARGET: Int = 8
        val USER_HANGOVER: Duration = 450.milliseconds
        const val BYTES_PER_SECOND: Int =
            AudioConstants.SAMPLE_RATE_HZ *
                AudioConstants.CHANNEL_COUNT *
                AudioConstants.BITS_PER_SAMPLE / 8
    }
}
