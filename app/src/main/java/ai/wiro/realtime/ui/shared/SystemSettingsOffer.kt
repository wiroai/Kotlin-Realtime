package ai.wiro.realtime.ui.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

object SystemSettingsCopy {
    const val OPEN: String = "Open settings"
    const val ALERT_TITLE: String = "Microphone access"
    const val DISMISS: String = "OK"
}

/** The alert a permanently denied microphone raises, once. */
@Composable
fun MicrophoneSettingsAlert(
    message: String?,
    onOpenSystemSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SystemSettingsCopy.ALERT_TITLE) },
        text = { message?.let { Text(it) } },
        confirmButton = {
            TextButton(onClick = onOpenSystemSettings) {
                Text(SystemSettingsCopy.OPEN)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(SystemSettingsCopy.DISMISS)
            }
        },
    )
}

/**
 * First time a permanent microphone denial appears, the screen
 * raises an alert. Dismissing it leaves the toolbar item.
 */
data class SystemSettingsOffer(
    val canOpen: Boolean = false,
    val showsAlert: Boolean = false,
    private val offered: Boolean = false,
) {
    fun apply(canOpen: Boolean): SystemSettingsOffer {
        val offerNow = canOpen && !offered
        return copy(
            offered = canOpen,
            canOpen = canOpen,
            showsAlert = when {
                !canOpen -> false
                offerNow -> true
                else -> showsAlert
            },
        )
    }

    fun dismissAlert(): SystemSettingsOffer = copy(showsAlert = false)
}

fun displayCatalogValue(value: String): String {
    val first = value.firstOrNull() ?: return value
    return first.uppercaseChar() + value.drop(1)
}
