package ai.wiro.realtime.realtime

import ai.wiro.realtime.networking.WiroEndpoints
import ai.wiro.realtime.networking.WiroFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class OkHttpRealtimeSocketClient(
    private val endpoint: String = WiroEndpoints.SOCKET_URL,
    private val connectTimeout: Duration = 10.seconds,
    client: OkHttpClient? = null,
) : RealtimeSocketClient {
    private val lock = Any()
    private val ownsClient: Boolean = client == null
    private val httpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var handler: RealtimeSocketHandler? = null
    private var connected: Boolean = false
    private var closed: Boolean = false
    private var handshake: CompletableDeferred<Unit>? = null

    override val isConnected: Boolean
        get() = synchronized(lock) { connected && !closed }

    override fun setHandler(handler: RealtimeSocketHandler?) {
        synchronized(lock) { this.handler = handler }
    }

    override suspend fun connect() {
        if (isConnected) {
            return
        }
        if (synchronized(lock) { closed }) {
            throw WiroFailure.SocketConnection
        }
        val waiter = CompletableDeferred<Unit>()
        val request = Request.Builder().url(endpoint).build()
        synchronized(lock) {
            handshake = waiter
        }
        RealtimeTrace.line("socket connecting $endpoint")
        val socket = httpClient.newWebSocket(request, Listener())
        synchronized(lock) { webSocket = socket }
        val opened = withTimeoutOrNull(connectTimeout) {
            waiter.await()
        }
        if (opened == null) {
            tearDownSocket()
            throw WiroFailure.SocketConnectTimeout
        }
        synchronized(lock) { connected = true }
        RealtimeTrace.line("socket handshake complete")
    }

    override suspend fun sendText(message: String) {
        val socket = connectedSocket()
        if (!socket.send(message)) {
            throw WiroFailure.SocketConnection
        }
    }

    override fun sendBinary(bytes: ByteArray) {
        val socket = connectedSocket()
        socket.send(ByteString.of(*bytes))
    }

    override suspend fun close() {
        val alreadyClosed = synchronized(lock) {
            if (closed) {
                return@synchronized true
            }
            closed = true
            connected = false
            false
        }
        if (alreadyClosed) {
            return
        }
        handshake?.completeExceptionally(WiroFailure.Cancelled)
        tearDownSocket()
        if (ownsClient) {
            httpClient.dispatcher.executorService.shutdown()
        }
    }

    private fun connectedSocket(): WebSocket {
        val socket = synchronized(lock) {
            if (!connected || closed) {
                null
            } else {
                webSocket
            }
        }
        return socket ?: throw WiroFailure.SocketConnection
    }

    private fun currentHandler(): RealtimeSocketHandler? = synchronized(lock) { handler }

    private fun tearDownSocket() {
        val socket = synchronized(lock) {
            val current = webSocket
            webSocket = null
            connected = false
            current
        }
        socket?.close(GOING_AWAY, null)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            handshake?.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            currentHandler()?.onFrame(RealtimeSocketFrame.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            currentHandler()?.onFrame(
                RealtimeSocketFrame.Binary(bytes.toByteArray()),
            )
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, reason)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            deliverDone()
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (!handshakeComplete()) {
                handshake?.completeExceptionally(
                    WiroFailure.SocketConnection,
                )
            }
            val current = synchronized(lock) {
                connected = false
                if (closed) null else handler
            }
            current?.onError(WiroFailure.SocketConnection)
            current?.onDone()
        }
    }

    private fun handshakeComplete(): Boolean = handshake?.isCompleted == true

    private fun deliverDone() {
        val current = synchronized(lock) {
            connected = false
            if (closed) null else handler
        }
        current?.onDone()
    }

    private companion object {
        const val GOING_AWAY: Int = 1001
    }
}
