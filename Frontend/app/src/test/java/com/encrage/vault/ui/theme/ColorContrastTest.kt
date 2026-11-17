package com.encrage.vault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {
    @Test
    fun lightThemeContentRolesMeetNormalTextContrast() = assertContentContrast("light", LightColors)

    @Test
    fun darkThemeContentRolesMeetNormalTextContrast() = assertContentContrast("dark", DarkColors)

    private fun assertContentContrast(theme: String, colors: ColorScheme) {
        val pairs = listOf(
            "primary" to (colors.onPrimary to colors.primary),
            "primary container" to (colors.onPrimaryContainer to colors.primaryContainer),
            "secondary" to (colors.onSecondary to colors.secondary),
            "secondary container" to (colors.onSecondaryContainer to colors.secondaryContainer),
            "tertiary" to (colors.onTertiary to colors.tertiary),
            "surface" to (colors.onSurface to colors.surface),
            "surface variant" to (colors.onSurfaceVariant to colors.surfaceVariant),
            "error" to (colors.onError to colors.error),
        )
        pairs.forEach { (role, pair) ->
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue("$theme $role contrast was $ratio", ratio >= 4.5f)
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
