package ai.wiro.realtime.ui.transcription

import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.ui.shared.displayCatalogValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechToTextUiStateTest {
    @Test
    fun connectCaptionsNameTheWaitThePersonIsIn() {
        assertEquals(
            TranscriptionCopy.CONNECTING_PERMISSION,
            TranscriptionConnectProgress.caption(
                RealtimeSessionState.RequestingPermission,
            ),
        )
        assertEquals(
            TranscriptionCopy.STARTING_TASK,
            TranscriptionConnectProgress.caption(
                RealtimeSessionState.CreatingTask,
            ),
        )
        assertEquals(
            TranscriptionCopy.FINISHING,
            TranscriptionConnectProgress.caption(
                RealtimeSessionState.Stopping,
            ),
        )
        assertNull(
            TranscriptionConnectProgress.caption(
                RealtimeSessionState.Streaming,
            ),
        )
        assertNull(
            TranscriptionConnectProgress.caption(RealtimeSessionState.Idle),
        )
    }

    @Test
    fun connectingIsOnlyTheWaitBetweenTapAndStream() {
        var state = SpeechToTextUiState(
            sessionState = RealtimeSessionState.CreatingTask,
        )
        assertTrue(state.showsConnecting)
        assertFalse(state.isLive)

        state = state.copy(sessionState = RealtimeSessionState.Streaming)
        assertFalse(state.showsConnecting)
        assertTrue(state.isLive)

        state = state.copy(sessionState = RealtimeSessionState.Stopping)
        assertTrue(state.isEnding)
        assertFalse(state.showsConnecting)
    }

    @Test
    fun clearIsIdleAndNonEmptyOnly() {
        var state = SpeechToTextUiState(transcript = "hello")
        assertTrue(state.canClearTranscript)

        state = state.copy(sessionState = RealtimeSessionState.Streaming)
        assertFalse(state.canClearTranscript)

        state = state.copy(
            sessionState = RealtimeSessionState.Completed,
            transcript = "",
        )
        assertFalse(state.canClearTranscript)
    }

    @Test
    fun copyActionIsTheDartWording() {
        assertEquals("Copy", TranscriptionCopy.COPY)
    }

    @Test
    fun displayNameCapitalizesTheFirstLetter() {
        assertEquals("Balanced", displayCatalogValue("balanced"))
        assertEquals("", displayCatalogValue(""))
    }
}
