package ai.wiro.realtime.ui.conversation

import ai.wiro.realtime.realtime.RealtimeSessionState

object ConversationCopy {
    const val DESTINATION_TITLE: String = "Conversation"
    const val CANCEL: String = "Cancel"
    const val STOP: String = "Stop"
    const val MUTE: String = "Mute"
    const val UNMUTE: String = "Unmute"
    const val INTERRUPT: String = "Silence assistant"
    const val VOICE_CHANGING: String = "Changing voice"
    const val SETTINGS: String = "Settings"
    const val CONNECTING: String = "Connecting…"
    const val CONNECTING_PERMISSION: String =
        "Waiting for microphone access…"
    const val STARTING_TASK: String = "Starting the task…"
    const val CONNECTING_STREAM: String = "Almost ready…"
    const val START_CTA: String = "Start conversation"
    const val TRY_AGAIN: String = "Try again"
    const val EMPTY_TITLE: String = "Talk it out"
    const val EMPTY_DETAIL: String =
        "A live voice conversation with the assistant,\nright from this screen."
    const val FAILURE_TITLE: String = "Something went wrong"
    const val LISTENING: String = "Listening…"
    const val HEARING_YOU: String = "Hearing you…"
    const val ASSISTANT_SPEAKING: String = "Assistant speaking…"
    const val MUTED_NOTE: String = "Microphone muted"
    const val ENDING: String = "Finishing…"
    const val YOU: String = "You"
    const val ASSISTANT: String = "Assistant"
}

/**
 * The wait between the tap and the first audible word, named.
 *
 * Permission can hang on a system dialog and a cold task can take
 * seconds, so a single "Connecting…" leaves no way to tell a
 * session that is coming from one that is stuck.
 */
object ConversationConnectProgress {
    fun caption(state: RealtimeSessionState): String? = when (state) {
        RealtimeSessionState.RequestingPermission ->
            ConversationCopy.CONNECTING_PERMISSION

        RealtimeSessionState.PreparingAudio ->
            ConversationCopy.CONNECTING

        RealtimeSessionState.CreatingTask ->
            ConversationCopy.STARTING_TASK

        RealtimeSessionState.ConnectingSocket ->
            ConversationCopy.CONNECTING

        RealtimeSessionState.WaitingForStream ->
            ConversationCopy.CONNECTING_STREAM

        else -> null
    }
}

object ConversationFields {
    const val VOICE_ID: String = "voice"
    const val INSTRUCTIONS_ID: String = "system_instructions"
}
