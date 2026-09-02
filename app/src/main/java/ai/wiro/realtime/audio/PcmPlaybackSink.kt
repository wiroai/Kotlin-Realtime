package ai.wiro.realtime.audio

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Voice-communication playback keeps the echo canceller in the
 * loop and follows the call route (earpiece unless rerouted).
 * Media playback is plain listening and comes out of the
 * loudspeaker by default.
 */
enum class PcmPlaybackProfile {
    Conversation,
    Media,
}

interface PcmPlaybackSink {
    val underruns: Flow<Unit>

    suspend fun initialize(profile: PcmPlaybackProfile)

    suspend fun play()

    suspend fun feed(chunk: ByteArray)

    suspend fun flush()

    suspend fun bufferedDuration(): Duration

    suspend fun setVolume(volume: Double)

    suspend fun endOfStream()

    suspend fun stop()

    suspend fun dispose()
}

interface PcmAudioPlayer {
    val isPlaying: Boolean
    val isEnabled: Boolean
    val failures: Flow<AudioFailure>
    val droppedChunks: Int
    val queueHighWaterMarkInChunks: Int
    val maximumFeedLatencyMicros: Int
    val underrunCount: Int

    suspend fun start()

    fun tryEnqueue(chunk: ByteArray): Boolean

    suspend fun enqueue(chunk: ByteArray)

    fun flush()

    suspend fun complete()

    suspend fun setEnabled(enabled: Boolean)

    suspend fun stop()

    suspend fun dispose()
}
