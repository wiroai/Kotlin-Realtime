package ai.wiro.realtime.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps steady background sound — a television, traffic, a fan — out
 * of the microphone stream while letting near-field speech through.
 *
 * Closed chunks are strongly attenuated instead of dropped or zeroed,
 * so the uplink keeps real-time pacing and carries room tone: the
 * server's voice detection hears a quiet room rather than a dead wire.
 */
class PcmNoiseGate(
    val minOpenLevel: Double = 0.28,
    val assistantOpenLevel: Double = 0.4,
    val floorMultiplier: Double = 1.6,
    val floorCap: Double = 0.5,
    val hangover: Duration = 600.milliseconds,
    val hangoverFraction: Double = 0.5,
    val flatnessLimit: Double = 0.15,
    val closedGain: Double = 0.05,
) {
    private val history = DoubleArray(HISTORY_LENGTH)
    private var historyCount: Int = 0
    private var historyCursor: Int = 0
    private var floor: Double = 0.0
    private var lastOutputLevelValue: Double = 0.0
    private var openUntilNanos: Long? = null

    val ambientFloor: Double
        get() = floor

    val lastOutputLevel: Double
        get() = lastOutputLevelValue

    val hearsHum: Boolean
        get() {
            if (historyCount < HISTORY_LENGTH) {
                return false
            }
            val mean = history.sum() / HISTORY_LENGTH
            if (mean <= 0) {
                return false
            }
            var squares = 0.0
            history.forEach { level ->
                val deviation = level - mean
                squares += deviation * deviation
            }
            val spread = sqrt(squares / HISTORY_LENGTH)
            return spread / mean < flatnessLimit
        }

    val isOpen: Boolean
        get() {
            val until = openUntilNanos ?: return false
            return System.nanoTime() < until
        }

    fun process(
        chunk: ByteArray,
        assistantAudible: Boolean = false,
    ): ByteArray {
        val level = pcmNormalizedLevel(chunk)
        history[historyCursor] = level
        historyCursor = (historyCursor + 1) % HISTORY_LENGTH
        if (historyCount < HISTORY_LENGTH) {
            historyCount += 1
        }
        val now = System.nanoTime()
        val baseOpenLevel = if (assistantAudible) {
            max(minOpenLevel, assistantOpenLevel)
        } else {
            minOpenLevel
        }
        val openThreshold = max(baseOpenLevel, floor * floorMultiplier)
        if (level >= openThreshold) {
            openUntilNanos = now + hangover.inWholeNanoseconds
            val alpha = if (hearsHum) {
                STEADY_CAPTURE_ALPHA
            } else {
                LOUD_CAPTURE_ALPHA
            }
            raiseFloor(level, alpha = alpha)
            lastOutputLevelValue = level
            return chunk
        }
        raiseFloor(level, alpha = if (level > floor) 0.04 else 0.12)
        val until = openUntilNanos
        if (
            until != null &&
            now < until &&
            level >= openThreshold * hangoverFraction
        ) {
            lastOutputLevelValue = level
            return chunk
        }
        lastOutputLevelValue = level * closedGain
        return attenuated(chunk)
    }

    fun reset() {
        floor = 0.0
        lastOutputLevelValue = 0.0
        history.fill(0.0)
        historyCount = 0
        historyCursor = 0
        openUntilNanos = null
    }

    private fun raiseFloor(level: Double, alpha: Double) {
        floor = min(floorCap, floor + (level - floor) * alpha)
    }

    private fun attenuated(chunk: ByteArray): ByteArray {
        val copy = chunk.copyOf()
        val buffer = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
        val samples = copy.size / AudioConstants.BYTES_PER_SAMPLE
        repeat(samples) {
            val scaled = buffer.getShort(it * 2).toInt() * closedGain
            buffer.putShort(it * 2, scaled.roundToInt().toShort())
        }
        return copy
    }

    private companion object {
        const val HISTORY_LENGTH: Int = 50
        const val LOUD_CAPTURE_ALPHA: Double = 0.001
        const val STEADY_CAPTURE_ALPHA: Double = 0.02
    }
}
