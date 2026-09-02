package ai.wiro.realtime.audio

import kotlinx.coroutines.flow.Flow

enum class PcmCaptureProfile {
    Conversation,
    Transcription,
}

interface PcmCaptureSource {
    val isRecording: Boolean

    suspend fun start(profile: PcmCaptureProfile): Flow<ByteArray>

    suspend fun stop()

    suspend fun dispose()
}

interface PcmAudioRecorder {
    val isRecording: Boolean
    val isMuted: Boolean
    val mutedSourceBuffers: Int
    val queueHighWaterMarkInChunks: Int

    suspend fun start(profile: PcmCaptureProfile): Flow<ByteArray>

    fun setMuted(muted: Boolean)

    suspend fun stop()

    suspend fun dispose()
}
