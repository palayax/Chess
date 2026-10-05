package net.palaya.chessanalyzer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = GreenPrimary,
    onPrimary = Color(0xFF0B1A02),
    primaryContainer = GreenContainer,
    onPrimaryContainer = OnDarkPrimary,
    secondary = ClassGreat,
    onSecondary = Color(0xFF001E30),
    // Selected segmented button / filter chip: used to fall back to the baseline purple.
    secondaryContainer = GreenContainer,
    onSecondaryContainer = OnDarkPrimary,
    tertiary = ClassBook,
    onTertiary = Color(0xFF2A1B0E),
    background = ChromeDark,
    onBackground = OnDarkPrimary,
    surface = SurfaceDark,
    onSurface = OnDarkPrimary,
    surfaceVariant = ElevatedDark,
    onSurfaceVariant = OnDarkSecondary,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = ElevatedDark,
    surfaceContainerHighest = ElevatedDarkHigh,
    surfaceContainerLow = ChromeDark,
    surfaceContainerLowest = Color(0xFF1C1B19),
    error = ErrorText,
    onError = Color(0xFF2B0500),
    outline = OutlineStrong,
    outlineVariant = Color(0xFF3A3833),
    inverseSurface = OnDarkPrimary,
    inverseOnSurface = ChromeDark,
)

private val LightColors = lightColorScheme(
    primary = GreenPressed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCF0C4),
    onPrimaryContainer = Color(0xFF0E2400),
    secondary = ClassGreat,
    onSecondary = Color.White,
    tertiary = ClassBook,
    onTertiary = Color.White,
    background = ChromeLight,
    onBackground = OnLightPrimary,
    surface = SurfaceLight,
    onSurface = OnLightPrimary,
    surfaceVariant = ElevatedLight,
    onSurfaceVariant = OnLightSecondary,
    error = ClassBlunder,
    onError = Color.White,
    outline = DividerLight,
)

/**
 * App-wide theme. Dark is the default regardless of system setting, matching the
 * chess.com-style product identity; pass [darkTheme] = false to force the light scheme
 * (e.g. from Settings, once a user preference is wired up in a later pass).
 */
@Composable
fun ChessAnalyzerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ChessAnalyzerTypography,
        shapes = ChessAnalyzerShapes,
        content = content,
    )
}
