package ai.wiro.realtime.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSettingsLinkTest {
    @Test
    fun opensTheApplicationDetailsSettingsAction() {
        assertEquals(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            AndroidSystemSettingsLink.ACTION,
        )
    }

    @Test
    fun preparationResultOnlyOffersSettingsForPermanentDenial() {
        assertTrue(
            AudioPreparationResult.PermissionPermanentlyDenied
                .needsSystemSettings,
        )
        assertEquals(
            AudioPermissionCopy.PERMANENTLY_DENIED,
            AudioPreparationResult.PermissionPermanentlyDenied
                .errorMessage,
        )
        assertEquals(
            false,
            AudioPreparationResult.PermissionDenied.needsSystemSettings,
        )
    }
}
