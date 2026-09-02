package ai.wiro.realtime.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Bounded FIFO of PCM chunks with overflow shed and backpressure.
 *
 * [tryAdd] never waits — a full queue drops the chunk and records it.
 * [add] waits for space so a slower consumer can catch up. [take]
 * waits for data or a close. Close releases every waiter.
 */
class BoundedPcmQueue(
    val capacityInChunks: Int,
) {
    init {
        require(capacityInChunks > 0) { "Queue capacity must be positive." }
    }

    private val lock = Any()
    private val chunks = ArrayDeque<ByteArray>()
    private val dataWaiters = ArrayDeque<Continuation<ByteArray?>>()
    private val spaceWaiters = ArrayDeque<Continuation<Unit>>()
    private var closed: Boolean = false
    private var highWater: Int = 0
    private var rejected: Int = 0

    val length: Int
        get() = synchronized(lock) { chunks.size }

    val highWaterMarkInChunks: Int
        get() = synchronized(lock) { highWater }

    val rejectedChunks: Int
        get() = synchronized(lock) { rejected }

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    fun tryAdd(chunk: ByteArray): Boolean {
        if (chunk.isEmpty()) {
            return true
        }
        val waiter = synchronized(lock) {
            if (closed || chunks.size >= capacityInChunks) {
                rejected += 1
                return false
            }
            enqueue(chunk)
        }
        waiter?.resume(chunk.copyOf())
        return true
    }

    suspend fun add(chunk: ByteArray) {
        if (chunk.isEmpty()) {
            return
        }
        while (true) {
            val (attempt, waiter) = synchronized(lock) {
                when {
                    closed -> AddAttempt.Closed to null

                    chunks.size < capacityInChunks ->
                        AddAttempt.Added to enqueue(chunk)

                    else -> AddAttempt.Full to null
                }
            }
            waiter?.resume(chunk.copyOf())
            when (attempt) {
                AddAttempt.Added, AddAttempt.Closed -> return

                AddAttempt.Full -> {
                    if (!parkForSpace()) {
                        return
                    }
                }
            }
        }
    }

    suspend fun take(): ByteArray? = suspendCancellableCoroutine { cont ->
        val (attempt, space) = synchronized(lock) {
            when {
                chunks.isNotEmpty() -> dequeue()

                closed -> TakeAttempt.Finished to emptyList()

                else -> {
                    dataWaiters.addLast(cont)
                    TakeAttempt.Waiting to emptyList()
                }
            }
        }
        cont.invokeOnCancellation { resumeCancelledTake() }
        when (attempt) {
            is TakeAttempt.Chunk -> {
                space.forEach { it.resume(Unit) }
                cont.resume(attempt.chunk)
            }

            TakeAttempt.Finished -> cont.resume(null)

            TakeAttempt.Waiting -> Unit
        }
    }

    fun chunks(): Flow<ByteArray> = flow {
        while (true) {
            val chunk = take() ?: break
            emit(chunk)
        }
    }

    fun clear() {
        val waiters = synchronized(lock) {
            chunks.clear()
            val current = spaceWaiters.toList()
            spaceWaiters.clear()
            current
        }
        waiters.forEach { it.resume(Unit) }
    }

    fun close() {
        val (data, space) = synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            val currentData = dataWaiters.toList()
            val currentSpace = spaceWaiters.toList()
            dataWaiters.clear()
            spaceWaiters.clear()
            currentData to currentSpace
        }
        data.forEach { it.resume(null) }
        space.forEach { it.resume(Unit) }
    }

    private fun enqueue(chunk: ByteArray): Continuation<ByteArray?>? {
        if (dataWaiters.isNotEmpty()) {
            if (highWater < 1) {
                highWater = 1
            }
            return dataWaiters.removeFirst()
        }
        chunks.addLast(chunk.copyOf())
        if (chunks.size > highWater) {
            highWater = chunks.size
        }
        return null
    }

    private fun dequeue(): Pair<TakeAttempt, List<Continuation<Unit>>> {
        val chunk = chunks.removeFirst()
        val waiters = spaceWaiters.toList()
        spaceWaiters.clear()
        return TakeAttempt.Chunk(chunk) to waiters
    }

    private suspend fun parkForSpace(): Boolean {
        suspendCancellableCoroutine { cont ->
            val shouldWait = synchronized(lock) {
                when {
                    closed -> false

                    chunks.size < capacityInChunks -> false

                    else -> {
                        spaceWaiters.addLast(cont)
                        true
                    }
                }
            }
            cont.invokeOnCancellation { resumeCancelledSpace() }
            if (!shouldWait) {
                cont.resume(Unit)
            }
        }
        return !synchronized(lock) { closed }
    }

    private fun resumeCancelledTake() {
        val waiter = synchronized(lock) {
            if (dataWaiters.isEmpty()) null else dataWaiters.removeLast()
        }
        waiter?.resume(null)
    }

    private fun resumeCancelledSpace() {
        val waiter = synchronized(lock) {
            if (spaceWaiters.isEmpty()) {
                null
            } else {
                spaceWaiters.removeLast()
            }
        }
        waiter?.resume(Unit)
    }

    private enum class AddAttempt { Added, Closed, Full }

    private sealed class TakeAttempt {
        data class Chunk(val chunk: ByteArray) : TakeAttempt()
        data object Finished : TakeAttempt()
        data object Waiting : TakeAttempt()
    }
}
