package ai.wiro.realtime.realtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeBinaryFrameTest {
    @Test
    fun encodesAndDecodesATokenPrefixedPcmFrame() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = RealtimeBinaryFrame.encode(
            taskToken = "task-token",
            payload = payload,
        )
        assertArrayEquals(
            "task-token|".toByteArray() + byteArrayOf(1, 2, 3),
            frame,
        )
        assertArrayEquals(
            payload,
            RealtimeBinaryFrame.decodePayload(
                taskToken = "task-token",
                frame = frame,
            ),
        )
    }

    @Test
    fun rejectsABinaryFrameForAnotherTaskToken() {
        val frame = RealtimeBinaryFrame.encode(
            taskToken = "other-token",
            payload = byteArrayOf(1, 2, 3),
        )
        assertNull(
            RealtimeBinaryFrame.decodePayload(
                taskToken = "task-token",
                frame = frame,
            ),
        )
        assertEquals('|'.code.toByte(), RealtimeBinaryFrame.separator)
    }
}
