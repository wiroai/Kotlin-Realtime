package ai.wiro.realtime.services

import kotlinx.coroutines.CompletableDeferred

/** One-shot wait used while session-end confirmation is in flight. */
internal class TerminalWait {
    private val lock = Any()
    private var waiter: CompletableDeferred<Unit>? = null
    var isCompleted: Boolean = false
        private set

    suspend fun wait() {
        val deferred = synchronized(lock) {
            if (isCompleted) {
                return
            }
            waiter ?: CompletableDeferred<Unit>().also { waiter = it }
        }
        deferred.await()
    }

    fun complete() {
        val deferred = synchronized(lock) {
            if (isCompleted) {
                return
            }
            isCompleted = true
            waiter
        }
        deferred?.complete(Unit)
    }
}
