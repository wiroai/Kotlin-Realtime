package ai.wiro.realtime.ui.speech

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.ui.conversation.ConversationStage
import ai.wiro.realtime.ui.conversation.conversationStage
import kotlin.time.Duration

data class TextToSpeechUiState(
    val sessionState: RealtimeSessionState = RealtimeSessionState.Idle,
    val selectedModel: ModelDefinition =
        WiroModelCatalog.mossTtsRealtime,
    val elapsed: Duration = Duration.ZERO,
    val isPlaying: Boolean = false,
    val prompt: String = "",
    val errorMessage: String? = null,
    val parameters: ModelParameterValues = ModelParameterValues(
        fields = WiroModelCatalog.mossTtsRealtime.firstPassFields,
    ),
) {
    val displayedStage: ConversationStage
        get() = sessionState.conversationStage

    val showsConnecting: Boolean
        get() = when (sessionState) {
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

    val canEditPrompt: Boolean
        get() = !sessionState.isActive

    val canStart: Boolean
        get() = sessionState.canStart && prompt.trim().isNotEmpty()

    val canChangeModel: Boolean
        get() = sessionState.isIdleLike
}
