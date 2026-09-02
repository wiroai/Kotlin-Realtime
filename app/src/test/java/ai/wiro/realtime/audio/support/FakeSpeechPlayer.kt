package ai.wiro.realtime.audio.support

import ai.wiro.realtime.audio.AudioFailure
import ai.wiro.realtime.audio.PcmAudioPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeSpeechPlayer(
    private val holdComplete: Boolean = false,
) : PcmAudioPlayer {
    private val lock = Any()
    private val failureEvents = MutableSharedFlow<AudioFailure>(
        extraBufferCapacity = 16,
    )
    private val fed = mutableListOf<ByteArray>()
    var startCalls: Int = 0
        private set
    var completeCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var flushCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set
    private var playing: Boolean = false
    private var enabled: Boolean = true
    private var completeReleased: Boolean = false
    private var completeGate: CompletableDeferred<Unit>? = null

    val fedChunks: List<ByteArray>
        get() = synchronized(lock) { fed.toList() }

    override val isPlaying: Boolean
        get() = synchronized(lock) { playing }

    override val isEnabled: Boolean
        get() = synchronized(lock) { enabled }

    override val failures: Flow<AudioFailure>
        get() = failureEvents.asSharedFlow()

    override val droppedChunks: Int = 0
    override val queueHighWaterMarkInChunks: Int = 0
    override val maximumFeedLatencyMicros: Int = 0
    override val underrunCount: Int = 0

    override suspend fun start() {
        synchronized(lock) {
            startCalls += 1
            playing = true
        }
    }

    override fun tryEnqueue(chunk: ByteArray): Boolean {
        val accept = synchronized(lock) { playing && enabled }
        if (!accept) {
            return false
        }
        synchronized(lock) { fed += chunk.copyOf() }
        return true
    }

    override suspend fun enqueue(chunk: ByteArray) {
        tryEnqueue(chunk)
    }

    override fun flush() {
        synchronized(lock) {
            flushCalls += 1
            fed.clear()
        }
    }

    override suspend fun complete() {
        val gate = synchronized(lock) {
            completeCalls += 1
            if (holdComplete && !completeReleased) {
                val created = CompletableDeferred<Unit>()
                completeGate = created
                created
            } else {
                playing = false
                null
            }
        }
        gate?.await()
        synchronized(lock) { playing = false }
    }

    fun releaseComplete() {
        val gate = synchronized(lock) {
            completeReleased = true
            completeGate.also { completeGate = null }
        }
        gate?.complete(Unit)
    }

    override suspend fun setEnabled(enabled: Boolean) {
        synchronized(lock) { this.enabled = enabled }
    }

    override suspend fun stop() {
        val gate = synchronized(lock) {
            if (!playing) {
                return
            }
            stopCalls += 1
            playing = false
            completeGate.also { completeGate = null }
        }
        gate?.complete(Unit)
    }

    override suspend fun dispose() {
        synchronized(lock) {
            disposeCalls += 1
            playing = false
        }
    }

    fun emitFailure() {
        failureEvents.tryEmit(AudioFailure.Playback)
    }
}
