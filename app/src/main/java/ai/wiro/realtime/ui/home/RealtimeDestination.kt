package ai.wiro.realtime.ui.home

enum class RealtimeDestination {
    Conversation,
    Transcription,
    Speech,
}

data class RealtimeHomeUiState(
    val destination: RealtimeDestination = RealtimeDestination.Conversation,
) {
    fun isVisible(destination: RealtimeDestination): Boolean = this.destination == destination
}
