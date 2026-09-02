package ai.wiro.realtime.networking

import org.junit.Assert.assertEquals
import org.junit.Test

class WiroEndpointsTest {
    @Test
    fun restAndSocketHostsMatchTheOfficialDocs() {
        assertEquals("https://api.wiro.ai/v1", WiroEndpoints.REST_BASE)
        assertEquals(
            "wss://socket.wiro.ai/v1",
            WiroEndpoints.SOCKET_URL,
        )
        assertEquals(
            "/Run/openai/gpt-realtime-mini",
            WiroEndpoints.runPath("openai", "gpt-realtime-mini"),
        )
        assertEquals("/Task/Cancel", WiroEndpoints.TASK_CANCEL)
        assertEquals("/Task/Kill", WiroEndpoints.TASK_KILL)
    }
}
