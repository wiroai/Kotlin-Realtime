package ai.wiro.realtime.audio.support

import ai.wiro.realtime.audio.PcmPlaybackProfile
import ai.wiro.realtime.audio.PcmPlaybackSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FakePcmPlaybackSink(
    val failFeed: Boolean = false,
    val stalled: Boolean = false,
    val feedDelay: Duration = Duration.ZERO,
) : PcmPlaybackSink {
    private val lock = Any()
    private val fed = mutableListOf<ByteArray>()
    private val recordedVolumes = mutableListOf<Double>()
    var initializeCalls: Int = 0
        private set
    var lastProfile: PcmPlaybackProfile? = null
        private set
    var playCalls: Int = 0
        private set
    var endOfStreamCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var flushCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set

    override val underruns: Flow<Unit> = emptyFlow()

    val fedChunks: List<ByteArray>
        get() = synchronized(lock) { fed.toList() }

    val volumes: List<Double>
        get() = synchronized(lock) { recordedVolumes.toList() }

    override suspend fun initialize(profile: PcmPlaybackProfile) {
        synchronized(lock) {
            initializeCalls += 1
            lastProfile = profile
        }
    }

    override suspend fun play() {
        synchronized(lock) { playCalls += 1 }
    }

    override suspend fun feed(chunk: ByteArray) {
        if (failFeed) {
            throw Exception("private platform failure")
        }
        if (feedDelay > Duration.ZERO) {
            delay(feedDelay)
        }
        synchronized(lock) { fed += chunk.copyOf() }
    }

    override suspend fun flush() {
        synchronized(lock) {
            flushCalls += 1
            fed.clear()
        }
    }

    override suspend fun bufferedDuration(): Duration = if (stalled) 30.seconds else Duration.ZERO

    override suspend fun setVolume(volume: Double) {
        synchronized(lock) { recordedVolumes += volume }
    }

    override suspend fun endOfStream() {
        synchronized(lock) { endOfStreamCalls += 1 }
    }

    override suspend fun stop() {
        synchronized(lock) { stopCalls += 1 }
    }

    override suspend fun dispose() {
        synchronized(lock) { disposeCalls += 1 }
    }
}
