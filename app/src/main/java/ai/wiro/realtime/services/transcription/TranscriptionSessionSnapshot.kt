package ai.wiro.realtime.services.transcription

import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.realtime.RealtimeSessionState
import kotlin.time.Duration

/**
 * Everything a transcription session knows about itself at one
 * moment. Visual state is excluded; tests read the session through
 * this alone.
 */
data class TranscriptionSessionSnapshot(
    val status: ViewStatus = ViewStatus.Initial,
    val sessionState: RealtimeSessionState = RealtimeSessionState.Idle,
    val elapsed: Duration = Duration.ZERO,
    val transcript: String = "",
    val microphoneActive: Boolean = false,
    val muted: Boolean = false,
    val errorMessage: String? = null,
    val canOpenSystemSettings: Boolean = false,
    val protocolEvents: List<String> = emptyList(),
    val revealProtocolPanel: Boolean = false,
)
