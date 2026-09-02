package ai.wiro.realtime.services.speech

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import kotlinx.coroutines.flow.Flow

/**
 * Commands a text-to-speech session and reports it as snapshots.
 *
 * The view model depends on this contract rather than on
 * [TextToSpeechSessionService] so it can be exercised without
 * audio hardware or a network stack.
 *
 * Every command is a no-op once [dispose] has run.
 */
interface SpeechSessionController {
    val snapshots: Flow<SpeechSessionSnapshot>
    val snapshot: SpeechSessionSnapshot
    val parameters: ModelParameterValues
    val sessionGuard: RealtimeSessionGuard
    var prompt: String

    fun selectModel(model: ModelDefinition)

    fun setParameter(id: String, value: String)

    suspend fun start()

    suspend fun stop()

    suspend fun dispose()
}
