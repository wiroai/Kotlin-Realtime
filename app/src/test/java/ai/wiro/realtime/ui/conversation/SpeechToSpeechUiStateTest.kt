package ai.wiro.realtime.ui.conversation

import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.ui.shared.formatElapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SpeechToSpeechUiStateTest {
    @Test
    fun connectCaptionsNameTheWaitThePersonIsIn() {
        assertEquals(
            ConversationCopy.CONNECTING_PERMISSION,
            ConversationConnectProgress.caption(
                RealtimeSessionState.RequestingPermission,
            ),
        )
        assertEquals(
            ConversationCopy.STARTING_TASK,
            ConversationConnectProgress.caption(
                RealtimeSessionState.CreatingTask,
            ),
        )
        assertEquals(
            ConversationCopy.CONNECTING_STREAM,
            ConversationConnectProgress.caption(
                RealtimeSessionState.WaitingForStream,
            ),
        )
        assertEquals(
            ConversationCopy.CONNECTING,
            ConversationConnectProgress.caption(
                RealtimeSessionState.PreparingAudio,
            ),
        )
        assertNull(
            ConversationConnectProgress.caption(
                RealtimeSessionState.Streaming,
            ),
        )
        assertNull(
            ConversationConnectProgress.caption(
                RealtimeSessionState.Idle,
            ),
        )
    }

    @Test
    fun elapsedCaptionIsMinutesAndSeconds() {
        assertEquals("1:30", formatElapsed(90.seconds))
    }
}
