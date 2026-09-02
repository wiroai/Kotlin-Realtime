package ai.wiro.realtime.realtime

import ai.wiro.realtime.audio.AudioFailure
import ai.wiro.realtime.networking.WiroFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RealtimeFailureMapperTest {
    private val mapper = RealtimeFailureMapper(
        fallbackMessage = "Session failed.",
    )

    @Test
    fun passesThroughWiroFailureMessagesAndCodes() {
        val failure = WiroFailure.SocketConnection
        assertEquals(failure.message, mapper.message(failure))
        assertEquals("socketConnection", mapper.code(failure))
    }

    @Test
    fun passesThroughAudioFailureMessagesAndCodes() {
        assertEquals(
            AudioFailure.Playback.message,
            mapper.message(AudioFailure.Playback),
        )
        assertEquals(
            "playbackUnavailable",
            mapper.code(AudioFailure.Playback),
        )
    }

    @Test
    fun collapsesUnrecognisedErrorsToTheFallback() {
        assertEquals(
            "Session failed.",
            mapper.message(CancellationException()),
        )
        assertEquals(
            RealtimeFailureMapper.UNKNOWN_CODE,
            mapper.code(CancellationException()),
        )
    }

    @Test
    fun neverLeaksRawExceptionDetailIntoTheMessage() {
        val leaky = Exception("tasktoken=super-secret-value")
        assertEquals("Session failed.", mapper.message(leaky))
        assertFalse(mapper.message(leaky).contains("secret"))
    }

    @Test
    fun eachFeatureCanSupplyItsOwnFallbackWording() {
        val transcription = RealtimeFailureMapper(
            fallbackMessage =
            "The transcription session could not be completed.",
        )
        assertEquals(
            "The transcription session could not be completed.",
            transcription.message(CancellationException()),
        )
        assertEquals(
            "Session failed.",
            mapper.message(CancellationException()),
        )
    }
}
