package ai.wiro.realtime.realtime

sealed class RealtimeSocketFrame {
    data class Text(
        val text: String,
    ) : RealtimeSocketFrame()

    data class Binary(
        val bytes: ByteArray,
    ) : RealtimeSocketFrame() {
        override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

data class RealtimeSocketHandler(
    val onFrame: (RealtimeSocketFrame) -> Unit,
    val onError: (Throwable) -> Unit,
    val onDone: () -> Unit,
)

interface RealtimeSocketClient {
    val isConnected: Boolean

    suspend fun connect()

    suspend fun sendText(message: String)

    fun sendBinary(bytes: ByteArray)

    suspend fun close()

    fun setHandler(handler: RealtimeSocketHandler?)
}
