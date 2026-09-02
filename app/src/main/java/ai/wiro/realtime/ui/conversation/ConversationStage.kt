package ai.wiro.realtime.ui.conversation

import ai.wiro.realtime.realtime.RealtimeSessionState

enum class ConversationStage {
    Ready,
    Connecting,
    Live,
    Ending,
    Failed,
}

val RealtimeSessionState.conversationStage: ConversationStage
    get() = when (this) {
        RealtimeSessionState.Idle,
        RealtimeSessionState.Completed,
        -> ConversationStage.Ready

        RealtimeSessionState.RequestingPermission,
        RealtimeSessionState.PreparingAudio,
        RealtimeSessionState.CreatingTask,
        RealtimeSessionState.ConnectingSocket,
        RealtimeSessionState.WaitingForStream,
        -> ConversationStage.Connecting

        RealtimeSessionState.Streaming -> ConversationStage.Live

        RealtimeSessionState.Stopping -> ConversationStage.Ending

        RealtimeSessionState.Failure -> ConversationStage.Failed
    }
