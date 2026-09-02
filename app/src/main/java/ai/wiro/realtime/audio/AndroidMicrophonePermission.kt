package ai.wiro.realtime.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidMicrophonePermission(
    private val activity: ComponentActivity,
) : MicrophonePermission {
    override suspend fun request(): MicrophonePermissionStatus {
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            return MicrophonePermissionStatus.Granted
        }
        return suspendCancellableCoroutine { continuation ->
            val launcher = activity.activityResultRegistry.register(
                "record-audio",
                ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                if (continuation.isActive) {
                    continuation.resume(statusAfterRequest(isGranted))
                }
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun statusAfterRequest(
        isGranted: Boolean,
    ): MicrophonePermissionStatus {
        if (isGranted) {
            return MicrophonePermissionStatus.Granted
        }
        val showRationale = ActivityCompat
            .shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            )
        return if (showRationale) {
            MicrophonePermissionStatus.Denied
        } else {
            MicrophonePermissionStatus.PermanentlyDenied
        }
    }
}

class AndroidSystemSettingsLink(
    private val activity: ComponentActivity,
) : SystemSettingsLink {
    override fun open() {
        val intent = android.content.Intent(ACTION).apply {
            data = android.net.Uri.fromParts(
                "package",
                activity.packageName,
                null,
            )
        }
        activity.startActivity(intent)
    }

    companion object {
        const val ACTION: String =
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    }
}
