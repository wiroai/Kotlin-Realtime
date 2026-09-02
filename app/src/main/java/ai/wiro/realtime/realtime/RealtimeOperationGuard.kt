package ai.wiro.realtime.realtime

/**
 * Invalidates in-flight realtime work whose result is no longer
 * wanted.
 *
 * Every asynchronous session operation claims an id through [begin].
 * Awaits inside that operation must re-check [isCurrent] before
 * touching shared state, because a newer operation — or [dispose] —
 * may have superseded it while the await was suspended.
 */
class RealtimeOperationGuard {
    private val lock = Any()
    private var currentId: Int = 0
    private var disposed: Boolean = false

    val current: Int
        get() = synchronized(lock) { currentId }

    val isDisposed: Boolean
        get() = synchronized(lock) { disposed }

    fun begin(): Int = synchronized(lock) {
        currentId += 1
        currentId
    }

    fun isCurrent(operationId: Int): Boolean = synchronized(lock) {
        !disposed && operationId == currentId
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            currentId += 1
        }
    }
}
