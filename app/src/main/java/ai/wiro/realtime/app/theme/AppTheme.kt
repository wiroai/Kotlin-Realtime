package ai.wiro.realtime.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KotlinRealtimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) {
            wiroDarkColorScheme()
        } else {
            wiroColorScheme()
        },
        content = content,
    )
}

/**
 * Wiro light tokens on Material 3 roles.
 *
 * The palette stays Wiro's own in both appearances instead of
 * dynamic color, so the showcase looks like the brand and matches
 * the Swift counterpart.
 */
fun wiroColorScheme(): ColorScheme = lightColorScheme(
    primary = AppColors.green,
    onPrimary = Color.White,
    primaryContainer = AppColors.greenContainer,
    onPrimaryContainer = AppColors.greenText,
    secondary = AppColors.greenMid,
    onSecondary = Color.White,
    secondaryContainer = AppColors.greenContainer,
    onSecondaryContainer = AppColors.greenText,
    background = AppColors.background,
    onBackground = AppColors.ink,
    surface = AppColors.surface,
    onSurface = AppColors.ink,
    surfaceVariant = AppColors.backgroundStone,
    onSurfaceVariant = AppColors.inkMuted,
    surfaceContainer = AppColors.backgroundWarm,
    surfaceContainerHigh = AppColors.backgroundStone,
    surfaceContainerLow = AppColors.background,
    outline = AppColors.inkDisabled,
    outlineVariant = AppColors.backgroundStone,
    error = AppColors.danger,
    onError = Color.White,
    errorContainer = AppColors.dangerContainer,
    onErrorContainer = AppColors.dangerText,
    inverseSurface = AppColors.darkSurface,
    inverseOnSurface = AppColors.onDark,
    inversePrimary = AppColors.greenBright,
)

/** The same Wiro tokens on a warm dark stage. */
fun wiroDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = AppColors.greenOnDark,
    onPrimary = AppColors.darkBackground,
    primaryContainer = AppColors.greenDarkContainer,
    onPrimaryContainer = AppColors.greenContainer,
    secondary = AppColors.greenBright,
    onSecondary = AppColors.darkBackground,
    secondaryContainer = AppColors.greenDarkContainer,
    onSecondaryContainer = AppColors.greenContainer,
    background = AppColors.darkBackground,
    onBackground = AppColors.onDark,
    surface = AppColors.darkSurface,
    onSurface = AppColors.onDark,
    surfaceVariant = AppColors.darkSurfaceHigh,
    onSurfaceVariant = AppColors.onDarkMuted,
    surfaceContainer = AppColors.darkSurfaceContainer,
    surfaceContainerHigh = AppColors.darkSurfaceHigh,
    surfaceContainerLow = AppColors.darkSurfaceLow,
    outline = AppColors.onDarkDisabled,
    outlineVariant = AppColors.darkSurfaceHigh,
    error = AppColors.dangerOnDark,
    onError = AppColors.darkBackground,
    errorContainer = AppColors.dangerDarkContainer,
    onErrorContainer = AppColors.dangerContainer,
    inverseSurface = AppColors.surface,
    inverseOnSurface = AppColors.ink,
    inversePrimary = AppColors.green,
)
