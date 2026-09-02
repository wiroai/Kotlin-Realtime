package ai.wiro.realtime.realtime.support

import ai.wiro.realtime.realtime.RealtimeSocketClient
import ai.wiro.realtime.realtime.RealtimeSocketFrame
import ai.wiro.realtime.realtime.RealtimeSocketHandler

class FakeRealtimeSocketClient : RealtimeSocketClient {
    val sentText: MutableList<String> = mutableListOf()
    val sentBinary: MutableList<ByteArray> = mutableListOf()
    var connectCalls: Int = 0
        private set
    var closeCalls: Int = 0
        private set
    private var connected: Boolean = false
    private var handler: RealtimeSocketHandler? = null

    override val isConnected: Boolean
        get() = connected

    override fun setHandler(handler: RealtimeSocketHandler?) {
        this.handler = handler
    }

    override suspend fun connect() {
        connectCalls += 1
        connected = true
    }

    override suspend fun sendText(message: String) {
        sentText += message
    }

    override fun sendBinary(bytes: ByteArray) {
        sentBinary += bytes
    }

    override suspend fun close() {
        if (!connected) {
            return
        }
        closeCalls += 1
        connected = false
    }

    fun emit(frame: RealtimeSocketFrame) {
        handler?.onFrame(frame)
    }

    fun emitText(json: String) {
        emit(RealtimeSocketFrame.Text(json))
    }

    fun finish() {
        connected = false
        handler?.onDone()
    }
}
