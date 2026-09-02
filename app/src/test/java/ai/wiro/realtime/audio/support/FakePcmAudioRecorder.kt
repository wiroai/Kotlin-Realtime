package ai.wiro.realtime.audio.support

import ai.wiro.realtime.audio.PcmAudioRecorder
import ai.wiro.realtime.audio.PcmCaptureProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakePcmAudioRecorder : PcmAudioRecorder {
    private val lock = Any()
    private var channel: Channel<ByteArray>? = null
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set
    override var isRecording: Boolean = false
        private set
    override var isMuted: Boolean = false
        private set
    override var mutedSourceBuffers: Int = 0
        private set
    override val queueHighWaterMarkInChunks: Int = 0

    override suspend fun start(
        profile: PcmCaptureProfile,
    ): Flow<ByteArray> {
        val created = Channel<ByteArray>(Channel.UNLIMITED)
        synchronized(lock) {
            startCalls += 1
            isRecording = true
            channel = created
        }
        return created.receiveAsFlow()
    }

    override fun setMuted(muted: Boolean) {
        synchronized(lock) { isMuted = muted }
    }

    override suspend fun stop() {
        val open = synchronized(lock) {
            if (!isRecording) {
                return
            }
            isRecording = false
            stopCalls += 1
            channel.also { channel = null }
        }
        open?.close()
    }

    override suspend fun dispose() {
        synchronized(lock) { disposeCalls += 1 }
        stop()
    }

    fun emit(bytes: ByteArray) {
        val current = synchronized(lock) { channel }
        val muted = synchronized(lock) { isMuted }
        if (current != null && !muted) {
            current.trySend(bytes)
        }
    }
}
