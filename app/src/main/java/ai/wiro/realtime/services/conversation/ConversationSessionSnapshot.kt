package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.realtime.RealtimeSessionState
import kotlin.time.Duration

/**
 * Everything a conversation session knows about itself at one
 * moment.
 *
 * The service's whole output surface. Transcripts and the clock live
 * here because a reconnect preserves what was said and the limit
 * watches the clock. Tests read the session through this alone.
 */
data class ConversationSessionSnapshot(
    val status: ViewStatus = ViewStatus.Initial,
    val sessionState: RealtimeSessionState = RealtimeSessionState.Idle,
    val elapsed: Duration = Duration.ZERO,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val microphoneActive: Boolean = false,
    val muted: Boolean = false,
    val isReconfiguring: Boolean = false,
    val isSuspended: Boolean = false,
    val errorMessage: String? = null,
    val canOpenSystemSettings: Boolean = false,
    val protocolEvents: List<String> = emptyList(),
)
