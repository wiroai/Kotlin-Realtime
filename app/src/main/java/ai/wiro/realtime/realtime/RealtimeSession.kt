package ai.wiro.realtime.realtime

import ai.wiro.realtime.networking.WiroFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface RealtimeSessionClient {
    val isReady: Boolean
    val isClosed: Boolean
    val metrics: RealtimeSessionMetrics

    fun addEventListener(listener: (RealtimeEvent) -> Unit)

    fun addErrorListener(listener: (Throwable) -> Unit)

    fun addDoneListener(listener: () -> Unit)

    suspend fun connect()

    fun sendAudio(pcmBytes: ByteArray): Boolean

    fun sendSessionEnd()

    suspend fun stop()

    suspend fun close()
}

class RealtimeSession(
    private val socketClient: RealtimeSocketClient,
    private val taskId: String,
    private val taskToken: String,
    private val parser: RealtimeEventParser = RealtimeEventParser(),
    private val readyTimeout: Duration = 15.seconds,
    private val closeTimeout: Duration = 3.seconds,
) : RealtimeSessionClient {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val metricsCollector = RealtimeSessionMetricsCollector()

    private var ready: Boolean = false
    private var closed: Boolean = false
    private var subscriptionSent: Boolean = false
    private var sessionEndSent: Boolean = false
    private var listeners: List<(RealtimeEvent) -> Unit> = emptyList()
    private var errorListeners: List<(Throwable) -> Unit> = emptyList()
    private var doneListeners: List<() -> Unit> = emptyList()
    private var doneNotified: Boolean = false
    private var readyWaiters: List<CompletableDeferred<Result<Unit>>> =
        emptyList()
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var closeDeferred: CompletableDeferred<Unit>? = null

    override val isReady: Boolean
        get() = synchronized(lock) { ready && !closed }

    override val isClosed: Boolean
        get() = synchronized(lock) { closed }

    override val metrics: RealtimeSessionMetrics
        get() = metricsCollector.snapshot()

    override fun addEventListener(listener: (RealtimeEvent) -> Unit) {
        synchronized(lock) { listeners = listeners + listener }
    }

    override fun addErrorListener(listener: (Throwable) -> Unit) {
        synchronized(lock) {
            errorListeners = errorListeners + listener
        }
    }

    override fun addDoneListener(listener: () -> Unit) {
        synchronized(lock) { doneListeners = doneListeners + listener }
    }

    override suspend fun connect() {
        val (deferred, shouldRun) = synchronized(lock) {
            val existing = connectDeferred
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<Unit>()
                connectDeferred = created
                created to true
            }
        }
        if (shouldRun) {
            try {
                performConnect()
                deferred.complete(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    deferred.completeExceptionally(WiroFailure.Cancelled)
                    throw error
                }
                deferred.completeExceptionally(error)
                throw error
            }
        }
        deferred.await()
    }

    override fun sendAudio(pcmBytes: ByteArray): Boolean {
        if (!isReady || pcmBytes.isEmpty()) {
            return false
        }
        return runCatching {
            socketClient.sendBinary(
                RealtimeBinaryFrame.encode(
                    taskToken = taskToken,
                    payload = pcmBytes,
                ),
            )
        }.isSuccess
    }

    override fun sendSessionEnd() {
        scope.launch { flushSessionEnd() }
    }

    override suspend fun stop() {
        flushSessionEnd()
        close()
    }

    override suspend fun close() {
        val (deferred, shouldRun) = synchronized(lock) {
            val existing = closeDeferred
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<Unit>()
                closeDeferred = created
                created to true
            }
        }
        if (shouldRun) {
            performClose()
            deferred.complete(Unit)
        }
        deferred.await()
    }

    private suspend fun performConnect() {
        if (synchronized(lock) { closed }) {
            throw WiroFailure.Cancelled
        }
        socketClient.setHandler(
            RealtimeSocketHandler(
                onFrame = { frame -> handleFrame(frame) },
                onError = { handleSocketError() },
                onDone = { handleSocketDone() },
            ),
        )
        RealtimeTrace.line("session connect task=$taskId")
        socketClient.connect()
        if (synchronized(lock) { closed }) {
            socketClient.close()
            throw WiroFailure.Cancelled
        }
        sendSubscription()
        try {
            waitUntilReady()
            RealtimeTrace.line("session stream ready")
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: WiroFailure) {
            if (error == WiroFailure.StreamReadyTimeout) {
                RealtimeTrace.line("session ready timeout")
                close()
            }
            throw error
        }
    }

    private suspend fun waitUntilReady() {
        if (isReady) {
            return
        }
        val completed = withTimeoutOrNull(readyTimeout) {
            awaitReady()
        }
        if (completed == null) {
            throw WiroFailure.StreamReadyTimeout
        }
    }

    private suspend fun awaitReady() {
        val waiter = CompletableDeferred<Result<Unit>>()
        synchronized(lock) {
            when {
                closed -> waiter.complete(
                    Result.failure(WiroFailure.Cancelled),
                )

                ready -> waiter.complete(Result.success(Unit))

                else -> readyWaiters = readyWaiters + waiter
            }
        }
        waiter.await().getOrThrow()
    }

    private suspend fun performClose() {
        val waiters = synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            ready = false
            val current = readyWaiters
            readyWaiters = emptyList()
            current
        }
        waiters.forEach { waiter ->
            waiter.complete(Result.failure(WiroFailure.Cancelled))
        }
        withTimeoutOrNull(closeTimeout) {
            socketClient.close()
        }
        socketClient.setHandler(null)
        notifyDone()
    }

    private suspend fun flushSessionEnd() {
        val shouldSend = synchronized(lock) {
            if (
                closed ||
                sessionEndSent ||
                !socketClient.isConnected
            ) {
                return@synchronized false
            }
            sessionEndSent = true
            true
        }
        if (!shouldSend) {
            return
        }
        runCatching {
            socketClient.sendText(
                encodeJson(
                    type = WiroRealtimeProtocol.TASK_SESSION_END,
                    taskToken = taskToken,
                ),
            )
        }
    }

    private suspend fun sendSubscription() {
        val shouldSend = synchronized(lock) {
            if (subscriptionSent) {
                return@synchronized false
            }
            subscriptionSent = true
            true
        }
        if (!shouldSend) {
            return
        }
        socketClient.sendText(
            encodeJson(
                type = WiroRealtimeProtocol.TASK_INFO,
                taskToken = taskToken,
            ),
        )
        RealtimeTrace.line("session sent task_info")
    }

    private fun handleFrame(frame: RealtimeSocketFrame) {
        if (synchronized(lock) { closed }) {
            return
        }
        val started = System.nanoTime()
        val event = normalize(parser.parse(frame))
        val micros = ((System.nanoTime() - started) / 1_000).toInt()
        metricsCollector.record(event, micros)
        val currentListeners = synchronized(lock) { listeners }
        currentListeners.forEach { listener -> listener(event) }
        when (event) {
            is RealtimeEvent.Status -> {
                if (event.type == RealtimeEventType.StreamReady) {
                    markReady()
                } else if (
                    event.type == RealtimeEventType.PostprocessEnded
                ) {
                    scope.launch { close() }
                }
            }

            RealtimeEvent.Cancel -> fail(WiroFailure.Cancelled)

            RealtimeEvent.Malformed -> fail(WiroFailure.RealtimeProtocol)

            is RealtimeEvent.Error,
            is RealtimeEvent.Output,
            is RealtimeEvent.Binary,
            is RealtimeEvent.Unknown,
            -> Unit
        }
    }

    private fun normalize(event: RealtimeEvent): RealtimeEvent {
        val bytes = (event as? RealtimeEvent.Binary)?.bytes
            ?: return event
        val payload = RealtimeBinaryFrame.decodePayload(
            taskToken = taskToken,
            frame = bytes,
        ) ?: RealtimeBinaryFrame.decodePayload(
            taskToken = taskId,
            frame = bytes,
        )
        return if (payload != null) {
            RealtimeEvent.Binary(payload)
        } else {
            RealtimeEvent.Malformed
        }
    }

    private fun markReady() {
        val waiters = synchronized(lock) {
            ready = true
            val current = readyWaiters
            readyWaiters = emptyList()
            current
        }
        waiters.forEach { it.complete(Result.success(Unit)) }
    }

    private fun handleSocketError() {
        if (synchronized(lock) { closed }) {
            return
        }
        notifyError(WiroFailure.SocketConnection)
        fail(WiroFailure.SocketConnection)
    }

    private fun handleSocketDone() {
        if (synchronized(lock) { closed }) {
            return
        }
        notifyDone()
        fail(WiroFailure.SocketConnection)
    }

    private fun notifyError(error: Throwable) {
        val current = synchronized(lock) { errorListeners }
        current.forEach { listener -> listener(error) }
    }

    private fun notifyDone() {
        val current = synchronized(lock) {
            if (doneNotified) {
                return@synchronized emptyList()
            }
            doneNotified = true
            doneListeners
        }
        current.forEach { listener -> listener() }
    }

    private fun fail(failure: WiroFailure) {
        val waiters = synchronized(lock) {
            val current = readyWaiters
            readyWaiters = emptyList()
            current
        }
        waiters.forEach { waiter ->
            waiter.complete(Result.failure(failure))
        }
        scope.launch { close() }
    }

    private fun encodeJson(
        type: String,
        taskToken: String,
    ): String = buildJsonObject {
        put("type", type)
        put("tasktoken", taskToken)
    }.toString()
}
