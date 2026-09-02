package ai.wiro.realtime.ui.speech

import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.services.speech.SpeechFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToSpeechUiStateTest {
    @Test
    fun connectCaptionsNameTheWaitThePersonIsIn() {
        assertEquals(
            SpeechCopy.STARTING_TASK,
            SpeechConnectProgress.caption(
                RealtimeSessionState.CreatingTask,
                isPlaying = false,
            ),
        )
        assertEquals(
            SpeechCopy.BUFFERING,
            SpeechConnectProgress.caption(
                RealtimeSessionState.Streaming,
                isPlaying = false,
            ),
        )
        assertEquals(
            SpeechCopy.SPEAKING,
            SpeechConnectProgress.caption(
                RealtimeSessionState.Streaming,
                isPlaying = true,
            ),
        )
        assertEquals(
            SpeechCopy.FINISHING,
            SpeechConnectProgress.caption(
                RealtimeSessionState.Stopping,
                isPlaying = false,
            ),
        )
        assertNull(
            SpeechConnectProgress.caption(
                RealtimeSessionState.Idle,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun connectingIsOnlyTheWaitBetweenTapAndStream() {
        var state = TextToSpeechUiState(
            sessionState = RealtimeSessionState.CreatingTask,
        )
        assertTrue(state.showsConnecting)
        assertFalse(state.isLive)
        assertFalse(state.canEditPrompt)

        state = state.copy(sessionState = RealtimeSessionState.Streaming)
        assertFalse(state.showsConnecting)
        assertTrue(state.isLive)

        state = state.copy(sessionState = RealtimeSessionState.Stopping)
        assertTrue(state.isEnding)
        assertFalse(state.showsConnecting)
    }

    @Test
    fun mossUsesTextAndVoxUsesPrompt() {
        assertEquals(
            "text",
            SpeechFields.promptFieldId(WiroModelCatalog.mossTtsRealtime),
        )
        assertEquals(
            "prompt",
            SpeechFields.promptFieldId(WiroModelCatalog.voxcpm2),
        )
        assertEquals(
            0,
            SpeechFields.settingsFields(WiroModelCatalog.mossTtsRealtime)
                .size,
        )
        assertEquals(
            listOf("cfgValue", "inferenceSteps"),
            SpeechFields.settingsFields(WiroModelCatalog.voxcpm2)
                .map { it.id },
        )
    }

    @Test
    fun characterCountIsCopyNotARawNumber() {
        assertEquals("12 characters", SpeechCopy.characterCount(12))
    }
}
