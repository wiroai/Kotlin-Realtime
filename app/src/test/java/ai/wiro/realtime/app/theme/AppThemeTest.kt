package ai.wiro.realtime.app.theme

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun schemeIsLightWiroEvenWhenThePhoneIsDark() {
        val scheme = wiroColorScheme()
        assertEquals(AppColors.background, scheme.background)
        assertEquals(AppColors.ink, scheme.onBackground)
        assertEquals(AppColors.green, scheme.primary)
        assertEquals(AppColors.greenContainer, scheme.secondaryContainer)
        assertEquals(AppColors.backgroundWarm, scheme.surfaceContainer)
        assertTrue(scheme.onSurface.luminance() < 0.5f)
    }
}
