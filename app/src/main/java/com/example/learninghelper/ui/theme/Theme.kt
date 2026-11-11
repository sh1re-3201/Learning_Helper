package com.example.learninghelper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Always-dark, true-black grayscale scheme (ignores system theme and wallpaper-based dynamic colors). */
private val MonochromeColorScheme = darkColorScheme(
    primary = MonoPrimary,
    onPrimary = MonoBlack,
    primaryContainer = MonoPrimaryContainer,
    onPrimaryContainer = MonoOnPrimaryContainer,
    secondary = MonoSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = MonoSecondaryContainer,
    onSecondaryContainer = MonoOnSecondaryContainer,
    tertiary = MonoTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = MonoTertiaryContainer,
    onTertiaryContainer = MonoOnTertiaryContainer,
    background = MonoBlack,
    onBackground = MonoOnSurface,
    surface = MonoBlack,
    onSurface = MonoOnSurface,
    surfaceVariant = MonoSurfaceVariant,
    onSurfaceVariant = MonoOnSurfaceVariant,
    surfaceTint = MonoPrimary,
    inverseSurface = MonoOnSurface,
    inverseOnSurface = MonoBlack,
    inversePrimary = MonoPrimaryContainer,
    outline = MonoOutline,
    outlineVariant = MonoOutlineVariant,
    scrim = MonoBlack,
    error = MonoError,
    onError = MonoBlack,
    errorContainer = MonoErrorContainer,
    onErrorContainer = MonoOnErrorContainer,
    surfaceBright = MonoSurfaceHighest,
    surfaceDim = MonoBlack,
    surfaceContainerLowest = MonoSurfaceLowest,
    surfaceContainerLow = MonoSurfaceLow,
    surfaceContainer = MonoSurfaceContainer,
    surfaceContainerHigh = MonoSurfaceHigh,
    surfaceContainerHighest = MonoSurfaceHighest,
)

@Composable
fun LearningHelperTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MonochromeColorScheme,
        typography = Typography,
        content = content
    )
}
