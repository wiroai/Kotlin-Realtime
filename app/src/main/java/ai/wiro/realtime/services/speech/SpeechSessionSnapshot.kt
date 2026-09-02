package ai.wiro.realtime.services.speech

import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.realtime.RealtimeSessionState
import kotlin.time.Duration

/**
 * Everything a text-to-speech session knows at one moment.
 * Visual state is excluded; tests read the session through this.
 */
data class SpeechSessionSnapshot(
    val selectedModel: ModelDefinition,
    val status: ViewStatus = ViewStatus.Initial,
    val sessionState: RealtimeSessionState = RealtimeSessionState.Idle,
    val elapsed: Duration = Duration.ZERO,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
    val protocolEvents: List<String> = emptyList(),
    val revealProtocolPanel: Boolean = false,
)
