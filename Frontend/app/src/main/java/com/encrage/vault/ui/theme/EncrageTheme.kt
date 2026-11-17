package com.encrage.vault.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF3F3C38),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E1DB),
    onPrimaryContainer = Color(0xFF211F1C),
    secondary = Color(0xFF5E5A55),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E2DC),
    onSecondaryContainer = Color(0xFF211F1C),
    tertiary = Color(0xFF555E5A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE5E1),
    onTertiaryContainer = Color(0xFF19201D),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFAF9F7),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFE6E2DD),
    onSurfaceVariant = Color(0xFF494642),
    outline = Color(0xFF77736E),
    outlineVariant = Color(0xFFC9C5BF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3F0),
    surfaceContainer = Color(0xFFEFEEEB),
    surfaceContainerHigh = Color(0xFFE9E7E3),
    surfaceContainerHighest = Color(0xFFE2E0DC),
    inverseSurface = Color(0xFF31302E),
    inverseOnSurface = Color(0xFFF4F0EC),
    inversePrimary = Color(0xFFCBC5BD),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFD1CCC5),
    onPrimary = Color(0xFF292724),
    primaryContainer = Color(0xFF47433F),
    onPrimaryContainer = Color(0xFFEEE9E3),
    secondary = Color(0xFFCAC5BE),
    onSecondary = Color(0xFF302E2A),
    secondaryContainer = Color(0xFF484540),
    onSecondaryContainer = Color(0xFFEAE6DF),
    tertiary = Color(0xFFC3CCC7),
    onTertiary = Color(0xFF29312E),
    tertiaryContainer = Color(0xFF404844),
    onTertiaryContainer = Color(0xFFE0E8E4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121211),
    onBackground = Color(0xFFE7E2DD),
    surface = Color(0xFF121211),
    onSurface = Color(0xFFE7E2DD),
    surfaceVariant = Color(0xFF494642),
    onSurfaceVariant = Color(0xFFCCC6BF),
    outline = Color(0xFF96918B),
    outlineVariant = Color(0xFF494642),
    surfaceContainerLowest = Color(0xFF0D0D0C),
    surfaceContainerLow = Color(0xFF1B1A19),
    surfaceContainer = Color(0xFF201F1E),
    surfaceContainerHigh = Color(0xFF2A2927),
    surfaceContainerHighest = Color(0xFF353330),
    inverseSurface = Color(0xFFE7E2DD),
    inverseOnSurface = Color(0xFF31302E),
    inversePrimary = Color(0xFF5F5B56),
)

@Composable
fun EncrageTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
