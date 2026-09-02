package ai.wiro.realtime.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class RealtimeSessionOwner {
    Conversation,
    Transcription,
    Speech,
}

data class RealtimeSessionLease(
    val owner: RealtimeSessionOwner,
    val id: Int,
)

class RealtimeSessionGuard {
    private class Waiter(
        val owner: RealtimeSessionOwner,
        val deferred: CompletableDeferred<RealtimeSessionLease?>,
    ) {
        fun complete(lease: RealtimeSessionLease?): Boolean = deferred.complete(lease)
    }

    private val lock = Any()
    private var lease: RealtimeSessionLease? = null
    private var nextId: Int = 0
    private var disposed: Boolean = false
    private var waiters: List<Waiter> = emptyList()

    val currentLease: RealtimeSessionLease?
        get() = synchronized(lock) { lease }

    fun acquire(owner: RealtimeSessionOwner): RealtimeSessionLease? {
        return synchronized(lock) {
            if (disposed || lease != null) {
                return@synchronized null
            }
            nextId += 1
            val created = RealtimeSessionLease(owner = owner, id = nextId)
            lease = created
            created
        }
    }

    suspend fun acquireWhenFree(
        owner: RealtimeSessionOwner,
        timeout: Duration = 5.seconds,
    ): RealtimeSessionLease? {
        acquire(owner)?.let { return it }
        if (synchronized(lock) { disposed }) {
            return null
        }
        val waiter = Waiter(
            owner = owner,
            deferred = CompletableDeferred(),
        )
        synchronized(lock) { waiters = waiters + waiter }
        acquire(owner)?.let { lease ->
            finish(waiter, lease)
            return waiter.deferred.await()
        }
        return coroutineScope {
            val timeoutJob = launch {
                delay(timeout)
                finish(waiter, null)
            }
            try {
                waiter.deferred.await()
            } finally {
                timeoutJob.cancel()
            }
        }
    }

    fun release(lease: RealtimeSessionLease?) {
        val shouldFulfill = synchronized(lock) {
            if (disposed || lease == null || this.lease != lease) {
                return@synchronized false
            }
            this.lease = null
            true
        }
        if (shouldFulfill) {
            fulfillWaiters()
        }
    }

    fun dispose() {
        val current = synchronized(lock) {
            disposed = true
            val snapshot = waiters
            waiters = emptyList()
            snapshot
        }
        current.forEach { waiter -> waiter.complete(null) }
    }

    private fun finish(
        waiter: Waiter,
        lease: RealtimeSessionLease?,
    ) {
        synchronized(lock) {
            waiters = waiters.filter { it !== waiter }
        }
        waiter.complete(lease)
    }

    private fun fulfillWaiters() {
        while (true) {
            val waiter = synchronized(lock) { waiters.firstOrNull() }
                ?: return
            if (synchronized(lock) { disposed }) {
                finish(waiter, null)
                continue
            }
            val acquired = acquire(waiter.owner)
            if (acquired != null) {
                finish(waiter, acquired)
                return
            }
            return
        }
    }
}
