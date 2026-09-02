package ai.wiro.realtime.audio

import kotlinx.coroutines.flow.Flow

enum class RealtimeAudioProfile {
    Conversation,
    Transcription,
    Speech,
    ;

    val requiresMicrophone: Boolean
        get() = when (this) {
            Conversation, Transcription -> true
            Speech -> false
        }
}

enum class AudioPreparationResult {
    Ready,
    PermissionDenied,
    PermissionPermanentlyDenied,
    PermissionRestricted,
    ActivationDenied,
    Unavailable,
    ;

    val needsSystemSettings: Boolean
        get() = this == PermissionPermanentlyDenied

    val errorMessage: String?
        get() = when (this) {
            Ready -> null

            PermissionDenied -> AudioPermissionCopy.DENIED

            PermissionPermanentlyDenied ->
                AudioPermissionCopy.PERMANENTLY_DENIED

            PermissionRestricted ->
                "Microphone access is restricted on this device."

            ActivationDenied ->
                "The audio session could not be activated. Try again."

            Unavailable ->
                "The audio session could not be prepared. Try again."
        }
}

enum class RealtimeAudioInterruption {
    Began,
    Ended,
}

interface RealtimeAudioSession {
    val interruptions: Flow<RealtimeAudioInterruption>
    val becomingNoisy: Flow<Unit>
    val routeChanges: Flow<Unit>

    suspend fun prepare(profile: RealtimeAudioProfile): AudioPreparationResult

    suspend fun deactivate()

    suspend fun setSpeakerEnabled(enabled: Boolean): Boolean

    suspend fun openSystemSettings(): Boolean

    suspend fun dispose()
}
