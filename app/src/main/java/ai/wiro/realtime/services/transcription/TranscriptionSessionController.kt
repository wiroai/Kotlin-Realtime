package ai.wiro.realtime.services.transcription

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import kotlinx.coroutines.flow.Flow

/**
 * Commands a transcription session and reports it as snapshots.
 *
 * The view model depends on this contract rather than on
 * [SpeechToTextSessionService] so it can be exercised without
 * audio hardware or a network stack.
 *
 * Every command is a no-op once [dispose] has run.
 */
interface TranscriptionSessionController {
    val snapshots: Flow<TranscriptionSessionSnapshot>
    val snapshot: TranscriptionSessionSnapshot
    val model: ModelDefinition
    val parameters: ModelParameterValues
    val sessionGuard: RealtimeSessionGuard

    suspend fun start()

    suspend fun stop()

    fun setMuted(muted: Boolean)

    fun setParameter(id: String, value: String)

    fun clearTranscript()

    suspend fun openSystemSettings()

    suspend fun dispose()
}
