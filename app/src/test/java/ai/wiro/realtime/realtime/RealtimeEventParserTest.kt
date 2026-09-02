package ai.wiro.realtime.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RealtimeEventParserTest {
    private val parser = RealtimeEventParser()

    @Test
    fun parsesKnownRealtimeStatusEvents() {
        val event = parser.parse(
            RealtimeSocketFrame.Text("{\"type\":\"task_stream_ready\"}"),
        )
        assertEquals(
            RealtimeEvent.Status(RealtimeEventType.StreamReady),
            event,
        )
    }

    @Test
    fun parsesProgressiveTranscriptOutput() {
        val event = parser.parse(
            RealtimeSocketFrame.Text(
                "{\"type\":\"task_output\"," +
                    "\"message\":\"TRANSCRIPT_USER:Hello\"}",
            ),
        )
        assertEquals(
            RealtimeEvent.Output("TRANSCRIPT_USER:Hello"),
            event,
        )
    }

    @Test
    fun doesNotRetainRawServerErrorDetails() {
        val event = parser.parse(
            RealtimeSocketFrame.Text(
                "{\"type\":\"task_error\"," +
                    "\"message\":\"secret server detail\"}",
            ),
        )
        assertEquals(RealtimeEvent.Error("task_error"), event)
        assertFalse(event.toString().contains("secret"))
    }

    @Test
    fun parsesBinaryFramesWithoutDecodingAudio() {
        val event = parser.parse(
            RealtimeSocketFrame.Binary(byteArrayOf(1, 2, 3)),
        )
        assertEquals(RealtimeEvent.Binary(byteArrayOf(1, 2, 3)), event)
    }

    @Test
    fun keepsUnknownEventTypesForwardCompatible() {
        val event = parser.parse(
            RealtimeSocketFrame.Text(
                "{\"type\":\"task_future_event\"}",
            ),
        )
        assertEquals(RealtimeEvent.Unknown("task_future_event"), event)
    }

    @Test
    fun mapsInvalidFramesToAMalformedEvent() {
        assertEquals(
            RealtimeEvent.Malformed,
            parser.parse(RealtimeSocketFrame.Text("not-json")),
        )
        assertEquals(
            RealtimeEvent.Malformed,
            parser.parse(
                RealtimeSocketFrame.Text(
                    "{\"message\":\"missing type\"}",
                ),
            ),
        )
    }
}
