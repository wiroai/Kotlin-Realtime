package ai.wiro.realtime.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plays PCM through a sink, with a bounded queue in front.
 *
 * [complete] waits out the queued tail for as long as the sink keeps
 * consuming. A sink that stopped draining — a torn-down engine, a
 * task the server just ended — must not hold the caller forever.
 */
class QueuedPcmAudioPlayer(
    private val sink: PcmPlaybackSink,
    private val profile: PcmPlaybackProfile =
        PcmPlaybackProfile.Conversation,
    private val queueCapacityInChunks: Int =
        AudioConstants.DEFAULT_QUEUE_CAPACITY_IN_CHUNKS,
    private val maxNativeBufferDuration: Duration = 2.seconds,
    private val backpressurePollInterval: Duration = 10.milliseconds,
    private val drainTimeout: Duration = 5.seconds,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : PcmAudioPlayer {
    private val lock = Any()
    private val failureEvents = MutableSharedFlow<AudioFailure>(
        extraBufferCapacity = 16,
    )
    private var queue: BoundedPcmQueue? = null
    private var consumeJob: Job? = null
    private var underrunJob: Job? = null
    private var playing: Boolean = false
    private var enabled: Boolean = true
    private var initialized: Boolean = false
    private var disposed: Boolean = false
    private var playbackGeneration: Int = 0
    private var dropped: Int = 0
    private var maxFeedLatency: Int = 0
    private var highWater: Int = 0
    private var underrunsSeen: Int = 0
    private var consumedChunks: Int = 0
    private var consumeFinished: Boolean = false

    init {
        underrunJob = scope.launch { observeUnderruns() }
    }

    override val isPlaying: Boolean
        get() = synchronized(lock) { playing }

    override val isEnabled: Boolean
        get() = synchronized(lock) { enabled }

    override val failures: Flow<AudioFailure>
        get() = failureEvents.asSharedFlow()

    override val droppedChunks: Int
        get() = synchronized(lock) { dropped }

    override val queueHighWaterMarkInChunks: Int
        get() = synchronized(lock) {
            queue?.highWaterMarkInChunks ?: highWater
        }

    override val maximumFeedLatencyMicros: Int
        get() = synchronized(lock) { maxFeedLatency }

    override val underrunCount: Int
        get() = synchronized(lock) { underrunsSeen }

    override suspend fun start() {
        if (synchronized(lock) { disposed }) {
            throw AudioFailure.Playback
        }
        if (synchronized(lock) { playing }) {
            return
        }
        try {
            sink.initialize(profile)
            val nextQueue = BoundedPcmQueue(queueCapacityInChunks)
            val currentlyEnabled = synchronized(lock) {
                initialized = true
                queue = nextQueue
                playing = true
                consumeFinished = false
                playbackGeneration += 1
                enabled
            }
            sink.setVolume(if (currentlyEnabled) 1.0 else 0.0)
            sink.play()
            consumeJob = scope.launch { consume(nextQueue) }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            synchronized(lock) {
                playing = false
                queue?.close()
            }
            throw AudioFailure.Playback
        }
    }

    override fun tryEnqueue(chunk: ByteArray): Boolean {
        val snapshot = synchronized(lock) {
            Triple(playing, enabled, queue)
        }
        if (!snapshot.first || chunk.isEmpty()) {
            return false
        }
        if (!snapshot.second) {
            return true
        }
        val added = snapshot.third?.tryAdd(chunk) ?: false
        if (!added) {
            synchronized(lock) { dropped += 1 }
        } else {
            val mark = snapshot.third?.highWaterMarkInChunks ?: 0
            synchronized(lock) {
                if (mark > highWater) {
                    highWater = mark
                }
            }
        }
        return added
    }

    override suspend fun enqueue(chunk: ByteArray) {
        val snapshot = synchronized(lock) {
            Triple(playing, enabled, queue)
        }
        if (!snapshot.first || !snapshot.second || chunk.isEmpty()) {
            return
        }
        snapshot.third?.add(chunk)
    }

    override fun flush() {
        if (!synchronized(lock) { playing }) {
            return
        }
        synchronized(lock) {
            playbackGeneration += 1
            queue?.clear()
        }
        scope.launch { flushSink() }
    }

    override suspend fun complete() {
        if (!synchronized(lock) { playing }) {
            return
        }
        synchronized(lock) { queue?.close() }
        val worker = synchronized(lock) { consumeJob }
        try {
            if (worker != null) {
                drainWhileProgressing()
            }
            if (!synchronized(lock) { playing } ||
                synchronized(lock) { disposed }
            ) {
                return
            }
            if (synchronized(lock) { enabled }) {
                withTimeoutOrNull(drainTimeout) {
                    sink.endOfStream()
                }
            }
            synchronized(lock) {
                playing = false
                queue = null
                consumeJob = null
            }
        } catch (_: Exception) {
            abandonTail()
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val changed = synchronized(lock) {
            if (this.enabled == enabled) {
                return@synchronized false
            }
            this.enabled = enabled
            playbackGeneration += 1
            if (!enabled) {
                queue?.clear()
            }
            true
        }
        if (!changed || !synchronized(lock) { initialized }) {
            return
        }
        try {
            if (enabled) {
                sink.setVolume(1.0)
                if (synchronized(lock) { playing }) {
                    sink.play()
                }
            } else {
                sink.setVolume(0.0)
                sink.stop()
            }
        } catch (_: Exception) {
            reportFailure()
        }
    }

    override suspend fun stop() {
        synchronized(lock) {
            playing = false
            playbackGeneration += 1
            queue?.clear()
            queue?.close()
        }
        if (synchronized(lock) { consumeJob } != null) {
            waitUntilFinishedOrTimeout(drainTimeout)
        }
        synchronized(lock) {
            queue = null
            consumeJob = null
        }
        if (synchronized(lock) { initialized }) {
            try {
                sink.stop()
            } catch (_: Exception) {
                reportFailure()
            }
        }
    }

    override suspend fun dispose() {
        if (synchronized(lock) { disposed }) {
            return
        }
        synchronized(lock) { disposed = true }
        stop()
        if (synchronized(lock) { initialized }) {
            sink.dispose()
        }
        synchronized(lock) { underrunJob }?.cancel()
    }

    private suspend fun consume(queue: BoundedPcmQueue) {
        try {
            queue.chunks()
                .takeWhile { synchronized(lock) { playing } }
                .collect { chunk ->
                    if (!synchronized(lock) { enabled }) {
                        return@collect
                    }
                    val generation = synchronized(lock) { playbackGeneration }
                    waitForNativeCapacity()
                    if (!synchronized(lock) { playing }) {
                        return@collect
                    }
                    val stale = synchronized(lock) {
                        !enabled || generation != playbackGeneration
                    }
                    if (stale) {
                        return@collect
                    }
                    val started = System.nanoTime()
                    sink.feed(chunk)
                    val micros = ((System.nanoTime() - started) / 1_000).toInt()
                    synchronized(lock) {
                        consumedChunks += 1
                        if (micros > maxFeedLatency) {
                            maxFeedLatency = micros
                        }
                    }
                }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            reportFailure()
        } finally {
            synchronized(lock) { consumeFinished = true }
        }
    }

    private suspend fun drainWhileProgressing() {
        var lastConsumed = synchronized(lock) { consumedChunks }
        var idle = Duration.ZERO
        while (true) {
            if (synchronized(lock) { consumeFinished }) {
                return
            }
            delay(10.milliseconds)
            idle += 10.milliseconds
            val consumed = synchronized(lock) { consumedChunks }
            if (consumed != lastConsumed) {
                lastConsumed = consumed
                idle = Duration.ZERO
            } else if (idle >= drainTimeout) {
                throw AudioFailure.Playback
            }
        }
    }

    private suspend fun waitForNativeCapacity() {
        while (synchronized(lock) { playing }) {
            if (sink.bufferedDuration() < maxNativeBufferDuration) {
                return
            }
            delay(backpressurePollInterval)
        }
    }

    private fun abandonTail() {
        synchronized(lock) {
            playing = false
            queue?.clear()
            queue?.close()
            queue = null
            consumeJob = null
        }
        scope.launch { stopAfterFailure() }
    }

    private suspend fun flushSink() {
        if (!synchronized(lock) { initialized }) {
            return
        }
        try {
            sink.flush()
        } catch (_: Exception) {
            reportFailure()
        }
    }

    private fun reportFailure() {
        synchronized(lock) {
            playing = false
            queue?.clear()
            queue?.close()
        }
        failureEvents.tryEmit(AudioFailure.Playback)
        scope.launch { stopAfterFailure() }
    }

    private suspend fun stopAfterFailure() {
        if (!synchronized(lock) { initialized }) {
            return
        }
        runCatching { sink.stop() }
    }

    private suspend fun observeUnderruns() {
        sink.underruns.collect {
            synchronized(lock) { underrunsSeen += 1 }
        }
    }

    private suspend fun waitUntilFinishedOrTimeout(timeout: Duration) {
        var elapsed = Duration.ZERO
        while (elapsed < timeout) {
            if (synchronized(lock) { consumeFinished }) {
                return
            }
            delay(10.milliseconds)
            elapsed += 10.milliseconds
        }
        synchronized(lock) { playing = false }
    }
}
