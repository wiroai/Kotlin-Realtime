package ai.wiro.realtime.realtime

object RealtimeBinaryFrame {
    val separator: Byte =
        WiroRealtimeProtocol.BINARY_PREFIX_SEPARATOR
            .first()
            .code
            .toByte()

    fun encode(
        taskToken: String,
        payload: ByteArray,
    ): ByteArray {
        val prefix = taskToken.toByteArray(Charsets.UTF_8)
        return prefix + byteArrayOf(separator) + payload
    }

    fun decodePayload(
        taskToken: String,
        frame: ByteArray,
    ): ByteArray? {
        val tokenBytes = taskToken.toByteArray(Charsets.UTF_8)
        val payloadOffset = tokenBytes.size + 1
        if (frame.size < payloadOffset) {
            return null
        }
        if (frame[tokenBytes.size] != separator) {
            return null
        }
        val prefix = frame.copyOfRange(0, tokenBytes.size)
        if (!prefix.contentEquals(tokenBytes)) {
            return null
        }
        return frame.copyOfRange(payloadOffset, frame.size)
    }
}
