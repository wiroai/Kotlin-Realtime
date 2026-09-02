package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.audio.PcmNoiseGate
import ai.wiro.realtime.audio.pcmNormalizedLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Gates microphone chunks, measures them, and notices when capture
 * dies.
 *
 * Healthy capture produces fifty chunks a second no matter how quiet
 * the room is, so a few silent seconds can only mean the pipeline
 * broke. The stall callback is pure timing.
 */
class ConversationMicPipeline(
    private val onStall: () -> Unit,
    private val scope: CoroutineScope,
    private val noiseGate: PcmNoiseGate? = null,
    private val stallTimeout: Duration = 3.seconds,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    data class Processed(val gated: ByteArray, val level: Double)

    private val lock = Any()
    private var watchdog: Job? = null
    private var lastChunkAt = Long.MIN_VALUE
    private var disposed = false

    fun process(chunk: ByteArray, assistantAudible: Boolean): Processed {
        synchronized(lock) { lastChunkAt = nowMillis() }
        val gate = noiseGate
        if (gate == null) {
            return Processed(chunk, pcmNormalizedLevel(chunk))
        }
        val gated = gate.process(chunk, assistantAudible = assistantAudible)
        return Processed(gated, gate.lastOutputLevel)
    }

    /**
     * Muted capture legitimately delivers nothing; the stall clock
     * only starts once the microphone is expected to speak again.
     */
    fun markAlive() {
        synchronized(lock) { lastChunkAt = nowMillis() }
    }

    fun startWatchdog() {
        synchronized(lock) {
            if (disposed) {
                return
            }
            stopWatchdogLocked()
            lastChunkAt = nowMillis()
            val tick = watchdogTick
            watchdog = scope.launch {
                while (isActive) {
                    delay(tick)
                    checkStall()
                }
            }
        }
    }

    fun stopWatchdog() {
        synchronized(lock) { stopWatchdogLocked() }
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            stopWatchdogLocked()
        }
    }

    private val watchdogTick: Duration
        get() {
            val half = stallTimeout / 2
            return if (half < 100.milliseconds) {
                100.milliseconds
            } else {
                half
            }
        }

    private fun stopWatchdogLocked() {
        watchdog?.cancel()
        watchdog = null
    }

    private fun checkStall() {
        val elapsed = synchronized(lock) {
            if (disposed) {
                return
            }
            nowMillis() - lastChunkAt
        }
        if (elapsed < stallTimeout.inWholeMilliseconds) {
            return
        }
        onStall()
    }
}
