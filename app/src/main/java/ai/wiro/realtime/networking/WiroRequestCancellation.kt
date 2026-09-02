package ai.wiro.realtime.networking

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

class WiroRequestCancellation {
    private val cancelled = AtomicBoolean(false)
    private val signal = CompletableDeferred<Unit>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            signal.complete(Unit)
        }
    }

    suspend fun awaitCancelled() {
        signal.await()
    }
}
