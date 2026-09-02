package ai.wiro.realtime.ui.transcription

import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.ui.conversation.ConversationStage
import ai.wiro.realtime.ui.conversation.conversationStage
import kotlin.time.Duration

data class SpeechToTextUiState(
    val sessionState: RealtimeSessionState = RealtimeSessionState.Idle,
    val elapsed: Duration = Duration.ZERO,
    val accuracy: String = WiroModelCatalog.voxtralMiniRealtime
        .firstPassFields
        .first { it.id == TranscriptionFields.DELAY_ID }
        .defaultValue
        ?: "balanced",
    val transcript: String = "",
    val muted: Boolean = false,
    val errorMessage: String? = null,
    val canOpenSystemSettings: Boolean = false,
    val showsSystemSettingsAlert: Boolean = false,
    val parameters: ModelParameterValues = ModelParameterValues(
        fields = WiroModelCatalog.voxtralMiniRealtime.firstPassFields,
    ),
) {
    val displayedStage: ConversationStage
        get() = sessionState.conversationStage

    val showsConnecting: Boolean
        get() = when (sessionState) {
            RealtimeSessionState.RequestingPermission,
            RealtimeSessionState.PreparingAudio,
            RealtimeSessionState.CreatingTask,
            RealtimeSessionState.ConnectingSocket,
            RealtimeSessionState.WaitingForStream,
            -> true

            else -> false
        }

    val isLive: Boolean
        get() = sessionState == RealtimeSessionState.Streaming

    val isEnding: Boolean
        get() = sessionState == RealtimeSessionState.Stopping

    val canClearTranscript: Boolean
        get() = sessionState.isIdleLike && transcript.isNotEmpty()

    val canStart: Boolean
        get() = sessionState.canStart
}
