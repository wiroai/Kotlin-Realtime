package ai.wiro.realtime.ui.conversation

import ai.wiro.realtime.app.ConversationConfig
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.realtime.RealtimeSessionState
import kotlin.time.Duration

data class SpeechToSpeechUiState(
    val sessionState: RealtimeSessionState = RealtimeSessionState.Idle,
    val elapsed: Duration = Duration.ZERO,
    val reportsConnecting: Boolean = false,
    val voice: String = ConversationConfig.VOICE,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val muted: Boolean = false,
    val isReconfiguring: Boolean = false,
    val errorMessage: String? = null,
    val canOpenSystemSettings: Boolean = false,
    val showsSystemSettingsAlert: Boolean = false,
    val parameters: ModelParameterValues =
        ConversationConfig.parameterValues(),
) {
    /**
     * Whether someone tapped Start and is still waiting to be heard.
     *
     * Raised by the view model's own start, never by a session
     * state. A dropped socket or voice change must not look like a
     * fresh connect.
     */
    val displayedStage: ConversationStage
        get() {
            val stage = sessionState.conversationStage
            if (
                !reportsConnecting &&
                stage == ConversationStage.Connecting
            ) {
                return ConversationStage.Live
            }
            return stage
        }

    val showsConnecting: Boolean
        get() = reportsConnecting &&
            sessionState.conversationStage == ConversationStage.Connecting

    val isLive: Boolean
        get() = displayedStage == ConversationStage.Live

    val isEnding: Boolean
        get() = displayedStage == ConversationStage.Ending

    val canStart: Boolean
        get() = displayedStage == ConversationStage.Ready ||
            displayedStage == ConversationStage.Failed

    val hasTranscripts: Boolean
        get() = userTranscript.isNotEmpty() || aiTranscript.isNotEmpty()
}
