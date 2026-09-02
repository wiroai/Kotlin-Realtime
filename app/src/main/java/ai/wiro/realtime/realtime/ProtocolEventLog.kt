package ai.wiro.realtime.realtime

/**
 * Bounded history of realtime protocol events surfaced in the debug
 * panel.
 *
 * Only the newest [maximumEvents] entries are retained so that a
 * long running session cannot grow view state without bound.
 */
class ProtocolEventLog(
    val maximumEvents: Int = 40,
) {
    init {
        require(maximumEvents > 0) { "maximumEvents must be positive" }
    }

    private val lock = Any()
    private var stored: List<String> = emptyList()

    val events: List<String>
        get() = synchronized(lock) { stored }

    fun add(event: String): List<String> = synchronized(lock) {
        var retained = stored
        if (retained.size >= maximumEvents) {
            retained = retained.takeLast(maximumEvents - 1)
        }
        retained = retained + event
        stored = retained
        stored
    }

    fun clear() {
        synchronized(lock) { stored = emptyList() }
    }
}
