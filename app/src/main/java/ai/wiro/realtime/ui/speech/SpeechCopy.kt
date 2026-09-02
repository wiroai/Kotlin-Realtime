package ai.wiro.realtime.ui.speech

import ai.wiro.realtime.realtime.RealtimeSessionState

object SpeechCopy {
    const val DESTINATION_TITLE: String = "Speech"
    const val PROMPT_HINT: String =
        "Type or paste the text to speak. Tap play when you are ready."
    const val CONNECTING: String = "Connecting…"
    const val PREPARING_AUDIO: String = "Preparing audio…"
    const val STARTING_TASK: String = "Starting the task…"
    const val CONNECTING_STREAM: String = "Almost ready…"
    const val BUFFERING: String = "Buffering…"
    const val SPEAKING: String = "Speaking…"
    const val FINISHING: String = "Finishing…"
    const val PLAY: String = "Play"
    const val STOP: String = "Stop"
    const val CANCEL: String = "Cancel"
    const val SETTINGS: String = "Settings"
    const val ENTER_TEXT: String = "Enter text to synthesize."

    fun characterCount(count: Int): String = "$count characters"
}

object SpeechConnectProgress {
    fun caption(
        state: RealtimeSessionState,
        isPlaying: Boolean,
    ): String? = when (state) {
        RealtimeSessionState.PreparingAudio -> SpeechCopy.PREPARING_AUDIO

        RealtimeSessionState.CreatingTask -> SpeechCopy.STARTING_TASK

        RealtimeSessionState.ConnectingSocket -> SpeechCopy.CONNECTING

        RealtimeSessionState.WaitingForStream ->
            SpeechCopy.CONNECTING_STREAM

        RealtimeSessionState.Streaming -> if (isPlaying) {
            SpeechCopy.SPEAKING
        } else {
            SpeechCopy.BUFFERING
        }

        RealtimeSessionState.Stopping -> SpeechCopy.FINISHING

        else -> null
    }
}
