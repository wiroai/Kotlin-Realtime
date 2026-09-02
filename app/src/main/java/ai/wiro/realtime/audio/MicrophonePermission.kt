package ai.wiro.realtime.audio

enum class MicrophonePermissionStatus {
    Granted,
    Denied,
    PermanentlyDenied,
}

fun interface MicrophonePermission {
    suspend fun request(): MicrophonePermissionStatus
}

fun interface SystemSettingsLink {
    fun open()
}

object AudioPermissionCopy {
    const val DENIED: String =
        "Microphone permission was denied. Allow access and try again."
    const val PERMANENTLY_DENIED: String =
        "Microphone access is disabled. Enable it in system settings."
}
