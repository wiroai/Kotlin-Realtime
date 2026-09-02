package ai.wiro.realtime.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class WiroRealtimeProtocolTest {
    @Test
    fun subscriptionAndEndFramesMatchTheOfficialDocs() {
        assertEquals("task_info", WiroRealtimeProtocol.TASK_INFO)
        assertEquals(
            "task_session_end",
            WiroRealtimeProtocol.TASK_SESSION_END,
        )
        assertEquals(
            "task_stream_ready",
            WiroRealtimeProtocol.TASK_STREAM_READY,
        )
        assertEquals(
            "TRANSCRIPT_USER:",
            WiroRealtimeProtocol.TRANSCRIPT_USER_PREFIX,
        )
        assertEquals(
            "TRANSCRIPT_AI:",
            WiroRealtimeProtocol.TRANSCRIPT_AI_PREFIX,
        )
        assertEquals("|", WiroRealtimeProtocol.BINARY_PREFIX_SEPARATOR)
    }
}
