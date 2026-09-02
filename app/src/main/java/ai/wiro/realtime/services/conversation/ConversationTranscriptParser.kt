package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.realtime.WiroRealtimeProtocol

sealed class ConversationTranscript {
    abstract val text: String

    data class User(override val text: String) : ConversationTranscript()

    data class Ai(override val text: String) : ConversationTranscript()
}

/**
 * Recognises the speaker prefixes the conversation model puts on its
 * transcript output. Both speakers share one output channel.
 */
class ConversationTranscriptParser {
    fun parse(message: String): ConversationTranscript? {
        val userPrefix = WiroRealtimeProtocol.TRANSCRIPT_USER_PREFIX
        if (message.startsWith(userPrefix)) {
            return ConversationTranscript.User(
                message.removePrefix(userPrefix),
            )
        }
        val aiPrefix = WiroRealtimeProtocol.TRANSCRIPT_AI_PREFIX
        if (message.startsWith(aiPrefix)) {
            return ConversationTranscript.Ai(
                message.removePrefix(aiPrefix),
            )
        }
        return null
    }
}
