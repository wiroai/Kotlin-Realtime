package ai.wiro.realtime.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Perceptual loudness of one 16-bit little-endian PCM chunk, 0...1.
 *
 * RMS is mapped through a power curve so normal speech spans most of
 * the range instead of huddling near zero. Every consumer of
 * microphone or assistant levels must read this same scale.
 */
fun pcmNormalizedLevel(chunk: ByteArray): Double {
    val sampleCount = chunk.size / AudioConstants.BYTES_PER_SAMPLE
    if (sampleCount <= 0) {
        return 0.0
    }
    val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
    var sumOfSquares = 0.0
    repeat(sampleCount) {
        val sample = buffer.short.toInt() / 32_768.0
        sumOfSquares += sample * sample
    }
    val rms = sqrt(sumOfSquares / sampleCount)
    return min(1.0, (rms * 5.0).pow(0.8))
}
