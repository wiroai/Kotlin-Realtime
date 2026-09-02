package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import kotlinx.coroutines.flow.Flow

/**
 * Commands a two-way speech conversation and reports it as snapshots.
 *
 * The view model depends on this contract rather than on
 * [SpeechToSpeechSessionService] so it can be exercised without
 * audio hardware or a network stack.
 *
 * Every command is a no-op once [dispose] has run.
 */
interface ConversationSessionController {
    val snapshots: Flow<ConversationSessionSnapshot>
    val snapshot: ConversationSessionSnapshot
    val model: ModelDefinition
    val parameters: ModelParameterValues
    val sessionGuard: RealtimeSessionGuard
    val voiceActivity: ConversationVoiceActivity

    suspend fun start()

    suspend fun stop()

    suspend fun changeVoice(voice: String)

    suspend fun suspend()

    suspend fun resume()

    fun setMuted(muted: Boolean)

    fun setParameter(id: String, value: String)

    fun interruptAssistant()

    suspend fun openSystemSettings()

    suspend fun dispose()
}
