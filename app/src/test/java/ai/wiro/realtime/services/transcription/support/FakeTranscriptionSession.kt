package ai.wiro.realtime.services.transcription.support

import ai.wiro.realtime.realtime.RealtimeEvent
import ai.wiro.realtime.realtime.RealtimeEventType
import ai.wiro.realtime.realtime.RealtimeSessionClient
import ai.wiro.realtime.realtime.RealtimeSessionMetrics

class FakeTranscriptionSession(
    private val completeSessionEnd: Boolean = true,
) : RealtimeSessionClient {
    private val lock = Any()
    private var eventListeners: List<(RealtimeEvent) -> Unit> = emptyList()
    private var errorListeners: List<(Throwable) -> Unit> = emptyList()
    private var doneListeners: List<() -> Unit> = emptyList()
    private val audio = mutableListOf<ByteArray>()
    var connectCalls: Int = 0
        private set
    var sessionEndCalls: Int = 0
        private set
    var closeCalls: Int = 0
        private set
    var connectError: Throwable? = null
    private var ready: Boolean = false
    private var closed: Boolean = false

    val sentAudio: List<ByteArray>
        get() = synchronized(lock) { audio.toList() }

    override val isReady: Boolean
        get() = synchronized(lock) { ready && !closed }

    override val isClosed: Boolean
        get() = synchronized(lock) { closed }

    override val metrics: RealtimeSessionMetrics
        get() = RealtimeSessionMetrics.Empty

    override fun addEventListener(listener: (RealtimeEvent) -> Unit) {
        synchronized(lock) { eventListeners = eventListeners + listener }
    }

    override fun addErrorListener(listener: (Throwable) -> Unit) {
        synchronized(lock) { errorListeners = errorListeners + listener }
    }

    override fun addDoneListener(listener: () -> Unit) {
        synchronized(lock) { doneListeners = doneListeners + listener }
    }

    override suspend fun connect() {
        synchronized(lock) { connectCalls += 1 }
        val error = synchronized(lock) {
            val current = connectError
            connectError = null
            current
        }
        if (error != null) {
            throw error
        }
        synchronized(lock) {
            closed = false
            ready = true
            sessionEndCalls = 0
        }
    }

    override fun sendAudio(pcmBytes: ByteArray): Boolean {
        if (!isReady) {
            return false
        }
        synchronized(lock) { audio += pcmBytes.copyOf() }
        return true
    }

    override fun sendSessionEnd() {
        val shouldEmit = synchronized(lock) {
            if (closed || sessionEndCalls > 0) {
                return
            }
            sessionEndCalls += 1
            completeSessionEnd
        }
        if (shouldEmit) {
            emit(RealtimeEvent.Status(RealtimeEventType.PostprocessEnded))
        }
    }

    override suspend fun stop() {
        sendSessionEnd()
        close()
    }

    override suspend fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                return@synchronized false
            }
            closeCalls += 1
            closed = true
            ready = false
            true
        }
        if (!shouldClose) {
            return
        }
    }

    fun emit(event: RealtimeEvent) {
        val listeners = synchronized(lock) { eventListeners }
        listeners.forEach { it(event) }
    }
}
