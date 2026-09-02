package ai.wiro.realtime.ui.transcription

import ai.wiro.realtime.realtime.RealtimeSessionState

object TranscriptionCopy {
    const val DESTINATION_TITLE: String = "Transcription"
    const val CANCEL: String = "Cancel"
    const val STOP: String = "Stop"
    const val MUTE: String = "Mute"
    const val UNMUTE: String = "Unmute"
    const val CLEAR: String = "Clear"
    const val COPY: String = "Copy"
    const val COPIED: String = "Transcript copied"
    const val CONNECTING: String = "Connecting…"
    const val CONNECTING_PERMISSION: String =
        "Waiting for microphone access…"
    const val STARTING_TASK: String = "Starting the task…"
    const val CONNECTING_STREAM: String = "Almost ready…"
    const val FINISHING: String = "Finishing…"
    const val FAILURE_TITLE: String = "Something went wrong"
    const val START_CTA: String = "Start transcribing"
    const val TRY_AGAIN: String = "Try again"
    const val EMPTY_TITLE: String = "Ready to transcribe"
    const val EMPTY_DETAIL: String =
        "Speak, and your words appear here as you talk."
    const val LISTENING: String = "Listening…"
    const val MUTED_NOTE: String = "Microphone muted"
}

object TranscriptionConnectProgress {
    fun caption(state: RealtimeSessionState): String? = when (state) {
        RealtimeSessionState.RequestingPermission ->
            TranscriptionCopy.CONNECTING_PERMISSION

        RealtimeSessionState.PreparingAudio ->
            TranscriptionCopy.CONNECTING

        RealtimeSessionState.CreatingTask ->
            TranscriptionCopy.STARTING_TASK

        RealtimeSessionState.ConnectingSocket ->
            TranscriptionCopy.CONNECTING

        RealtimeSessionState.WaitingForStream ->
            TranscriptionCopy.CONNECTING_STREAM

        RealtimeSessionState.Stopping -> TranscriptionCopy.FINISHING

        else -> null
    }
}

object TranscriptionFields {
    const val DELAY_ID: String = "transcriptionDelay"
}
