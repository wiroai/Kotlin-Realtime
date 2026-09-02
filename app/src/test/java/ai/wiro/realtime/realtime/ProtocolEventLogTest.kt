package ai.wiro.realtime.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolEventLogTest {
    @Test
    fun appendsEventsOldestFirst() {
        val log = ProtocolEventLog()
        log.add("first")
        val events = log.add("second")

        assertEquals(listOf("first", "second"), events)
        assertEquals(listOf("first", "second"), log.events)
    }

    @Test
    fun dropsTheOldestEventsPastTheMaximum() {
        val log = ProtocolEventLog(maximumEvents = 3)
        listOf("a", "b", "c", "d", "e").forEach { log.add(it) }
        assertEquals(listOf("c", "d", "e"), log.events)
    }

    @Test
    fun neverExceedsTheDefaultMaximumAcrossALongSession() {
        val log = ProtocolEventLog()
        repeat(500) { index ->
            log.add("event_$index")
        }
        assertEquals(40, log.events.size)
        assertEquals("event_460", log.events.first())
        assertEquals("event_499", log.events.last())
    }

    @Test
    fun clearResetsTheHistoryWithoutBreakingLaterAppends() {
        val log = ProtocolEventLog()
        log.add("stale")
        log.clear()
        assertTrue(log.events.isEmpty())
        assertEquals(listOf("fresh"), log.add("fresh"))
    }
}
