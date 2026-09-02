package ai.wiro.realtime.audio

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PCM wire format from Wiro realtime docs, verified 2026-08-31.
 *
 * Samples are signed 16-bit little-endian on both directions of the
 * stream; changing the rate means changing it in the Run request and
 * in the local pipeline together, or the audio is garbled.
 */
object AudioConstants {
    const val SAMPLE_RATE_HZ: Int = 24_000
    const val CHANNEL_COUNT: Int = 1
    const val BITS_PER_SAMPLE: Int = 16
    val CHUNK_DURATION: Duration = 20.milliseconds
    const val SAMPLES_PER_CHUNK: Int = 480
    const val PCM_BYTES_PER_CHUNK: Int = 960
    const val BYTES_PER_SAMPLE: Int = BITS_PER_SAMPLE / 8
    const val PREFERRED_IO_BUFFER_SECONDS: Double = 0.02
    const val DEFAULT_QUEUE_CAPACITY_IN_CHUNKS: Int = 50
}

/**
 * How much written PCM is still sitting in the sink.
 *
 * Written minus the playback head, never a running total of every
 * byte ever fed. A growing total made [QueuedPcmAudioPlayer] wait
 * forever after two seconds of speech, so the assistant said a
 * few words and went silent.
 */
internal fun pcmBufferedDuration(
    writtenFrames: Long,
    playbackHeadFrames: Long,
    sampleRateHz: Int = AudioConstants.SAMPLE_RATE_HZ,
): Duration {
    val pending = (writtenFrames - playbackHeadFrames).coerceAtLeast(0)
    if (pending <= 0 || sampleRateHz <= 0) {
        return Duration.ZERO
    }
    return (pending.toDouble() / sampleRateHz).seconds
}
