package ai.wiro.realtime.audio.support

import ai.wiro.realtime.audio.PcmCaptureProfile
import ai.wiro.realtime.audio.PcmCaptureSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakePcmCaptureSource : PcmCaptureSource {
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

    override suspend fun stop() {
        val shouldCount = synchronized(lock) {
            if (!isRecording) {
                return
            }
            isRecording = false
            stopCalls += 1
            true
        }
        if (shouldCount) {
            finish()
        }
    }

    override suspend fun dispose() {
        synchronized(lock) { disposeCalls += 1 }
        stop()
    }

    fun emit(bytes: ByteArray) {
        synchronized(lock) { channel }?.trySend(bytes)
    }

    fun emitError(error: Throwable) {
        val current = synchronized(lock) {
            val open = channel
            channel = null
            isRecording = false
            open
        }
        current?.close(error)
    }

    private fun finish() {
        val current = synchronized(lock) {
            val open = channel
            channel = null
            open
        }
        current?.close()
    }
}
