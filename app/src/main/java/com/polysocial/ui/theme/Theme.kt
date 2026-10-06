// Contributors: Claude (v2 colour scheme, shapes, light-only theme, container roles).
package com.polysocial.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorScheme =
    lightColorScheme(
        primary = Ink,
        onPrimary = Bg,
        primaryContainer = Ink,
        onPrimaryContainer = Bg,
        secondary = Accent,
        onSecondary = Bg,
        secondaryContainer = AccentSoft,
        onSecondaryContainer = AccentText,
        tertiary = Info,
        onTertiary = Bg,
        tertiaryContainer = InfoSoft,
        onTertiaryContainer = Info,
        background = Bg,
        onBackground = Ink,
        surface = Bg,
        onSurface = Ink,
        surfaceVariant = Surface,
        onSurfaceVariant = Ink2,
        // Figma surfaces (fields, cards, nav, sheets) are white with a border, never tinted.
        surfaceTint = Bg,
        surfaceBright = Bg,
        surfaceContainerLowest = Bg,
        surfaceContainerLow = Bg,
        surfaceContainer = Bg,
        surfaceContainerHigh = Bg,
        surfaceContainerHighest = Bg,
        inverseSurface = Ink,
        inverseOnSurface = Bg,
        inversePrimary = AccentOnInk,
        outline = Border,
        outlineVariant = Border,
        error = Accent,
        onError = Bg,
        errorContainer = AccentSoft,
        onErrorContainer = AccentText,
    )

/** v2 corner radii: fields (12), cards (16), buttons (26, fully rounded). */
val PolySocialShapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(26.dp),
    )

/**
 * App theme with the v2 design system. Light only and without dynamic colour, so every screen
 * matches the Figma design exactly.
 */
@Composable
fun PolySocialTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme = ColorScheme,
      typography = Typography,
      shapes = PolySocialShapes,
      content = content,
  )
}
