package ai.wiro.realtime.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Chunks a capture source into 20 ms PCM frames behind a bounded queue.
 */
class BufferedPcmAudioRecorder(
    private val source: PcmCaptureSource,
    private val queueCapacityInChunks: Int =
        AudioConstants.DEFAULT_QUEUE_CAPACITY_IN_CHUNKS,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : PcmAudioRecorder {
    private val lock = Any()
    private var queue: BoundedPcmQueue? = null
    private var chunker: PcmChunker? = null
    private var sourceJob: Job? = null
    private var captureFailure: AudioFailure? = null
    private var recording: Boolean = false
    private var muted: Boolean = false
    private var disposed: Boolean = false
    private var captureGeneration: Int = 0
    private var mutedBuffers: Int = 0
    private var highWater: Int = 0
    private var output: Flow<ByteArray>? = null

    override val isRecording: Boolean
        get() = synchronized(lock) { recording }

    override val isMuted: Boolean
        get() = synchronized(lock) { muted }

    override val mutedSourceBuffers: Int
        get() = synchronized(lock) { mutedBuffers }

    override val queueHighWaterMarkInChunks: Int
        get() = synchronized(lock) {
            queue?.highWaterMarkInChunks ?: highWater
        }

    override suspend fun start(
        profile: PcmCaptureProfile,
    ): Flow<ByteArray> {
        synchronized(lock) {
            if (disposed) {
                throw AudioFailure.Capture
            }
            val existing = output
            if (recording && existing != null) {
                return existing
            }
        }
        val nextQueue = BoundedPcmQueue(queueCapacityInChunks)
        val nextChunker = PcmChunker(AudioConstants.PCM_BYTES_PER_CHUNK)
        synchronized(lock) {
            queue = nextQueue
            chunker = nextChunker
            captureFailure = null
            captureGeneration += 1
        }
        val sourceStream = try {
            source.start(profile)
        } catch (_: Exception) {
            nextQueue.close()
            throw AudioFailure.Capture
        }
        val created = makeOutput(nextQueue)
        synchronized(lock) {
            recording = true
            output = created
            sourceJob = scope.launch { pump(sourceStream) }
        }
        return created
    }

    override fun setMuted(muted: Boolean) {
        synchronized(lock) {
            this.muted = muted
            if (muted) {
                captureGeneration += 1
                queue?.clear()
                chunker?.reset()
            }
        }
    }

    override suspend fun stop() {
        val job = synchronized(lock) {
            recording = false
            captureGeneration += 1
            sourceJob
        }
        job?.cancel()
        job?.join()
        synchronized(lock) {
            sourceJob = null
            queue?.clear()
            queue?.close()
            chunker?.reset()
            output = null
        }
        source.stop()
    }

    override suspend fun dispose() {
        if (synchronized(lock) { disposed }) {
            return
        }
        synchronized(lock) { disposed = true }
        stop()
        source.dispose()
    }

    private suspend fun pump(sourceStream: Flow<ByteArray>) {
        try {
            sourceStream.collect { bytes ->
                handleSourceData(bytes)
            }
            synchronized(lock) {
                recording = false
                queue?.close()
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            synchronized(lock) {
                captureFailure = AudioFailure.Capture
                recording = false
                queue?.close()
            }
            source.stop()
        }
    }

    private suspend fun handleSourceData(bytes: ByteArray) {
        val snapshot = synchronized(lock) {
            Snapshot(
                recording = recording,
                muted = muted,
                generation = captureGeneration,
                queue = queue,
                chunker = chunker,
            )
        }
        if (!snapshot.recording) {
            return
        }
        if (snapshot.muted) {
            synchronized(lock) { mutedBuffers += 1 }
            return
        }
        val currentQueue = snapshot.queue ?: return
        val currentChunker = snapshot.chunker ?: return
        val generation = snapshot.generation
        currentChunker.add(bytes).forEach { chunk ->
            if (!isCurrent(generation)) {
                return
            }
            currentQueue.add(chunk)
            val mark = currentQueue.highWaterMarkInChunks
            synchronized(lock) {
                if (mark > highWater) {
                    highWater = mark
                }
            }
            if (!isCurrent(generation)) {
                currentQueue.clear()
                return
            }
        }
    }

    private fun isCurrent(generation: Int): Boolean = synchronized(lock) {
        recording && !muted && generation == captureGeneration
    }

    private fun makeOutput(queue: BoundedPcmQueue): Flow<ByteArray> = channelFlow {
        queue.chunks().collect { chunk -> send(chunk) }
        val failure = synchronized(lock) { captureFailure }
        if (failure != null) {
            throw failure
        }
    }.catch { error ->
        if (error is AudioFailure) {
            throw error
        }
        throw AudioFailure.Capture
    }

    private data class Snapshot(
        val recording: Boolean,
        val muted: Boolean,
        val generation: Int,
        val queue: BoundedPcmQueue?,
        val chunker: PcmChunker?,
    )
}
