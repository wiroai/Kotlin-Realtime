package ai.wiro.realtime.app

import ai.wiro.realtime.models.ModelRequestValue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration

class ConversationConfigTest {
    @Test
    fun personaAndVoiceWinOverParameterOverrides() {
        val parameters = ConversationConfig.parameterValues(
            overrides = mapOf(
                "voice" to "cedar",
                "system_instructions" to "ignored",
                "transcription_model" to "whisper-1",
            ),
        )
        val snapshot = parameters.requestSnapshot()
        assertEquals(
            ModelRequestValue.Text(ConversationConfig.PERSONA),
            snapshot["system_instructions"],
        )
        assertEquals(
            ModelRequestValue.Text(ConversationConfig.VOICE),
            snapshot["voice"],
        )
        assertEquals(
            ModelRequestValue.Text("whisper-1"),
            snapshot["transcription_model"],
        )
    }

    @Test
    fun leaveScreenEndsTheConversation() {
        assertEquals(Duration.ZERO, ConversationConfig.suspendGracePeriod)
    }
}
