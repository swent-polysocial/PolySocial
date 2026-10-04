// Contributors: Claude (v2 type scale from Figma "First proposal revamped"); Claude Opus 5.5 (font
// for the Material styles the v2 sheet does not define).
package com.polysocial.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.polysocial.R

/** Plus Jakarta Sans, bundled as one variable font (weight axis 200–800). */
val PlusJakartaSans =
    FontFamily(
        listOf(
                FontWeight.Normal,
                FontWeight.Medium,
                FontWeight.SemiBold,
                FontWeight.Bold,
                FontWeight.ExtraBold,
            )
            .map {
              Font(
                  R.font.plus_jakarta_sans,
                  weight = it,
                  variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)),
              )
            }
    )

private fun style(
    weight: FontWeight,
    size: Int,
    lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = 0.sp,
) =
    TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
    )

/** Material's default type scale, for the styles the v2 sheet does not define. */
private val MaterialDefaults = Typography()

/**
 * v2 type scale. Material slots map to the Figma styles; an unspecified line height uses the font's
 * natural line height, like Figma's "auto". The styles v2 does not define keep Material's sizes but
 * still use Plus Jakarta Sans, so no text falls back to the system font.
 */
val Typography =
    Typography(
        displayLarge = MaterialDefaults.displayLarge.copy(fontFamily = PlusJakartaSans),
        displayMedium = MaterialDefaults.displayMedium.copy(fontFamily = PlusJakartaSans),
        headlineLarge = MaterialDefaults.headlineLarge.copy(fontFamily = PlusJakartaSans),
        headlineSmall = MaterialDefaults.headlineSmall.copy(fontFamily = PlusJakartaSans),
        displaySmall = style(FontWeight.ExtraBold, 28, 34.sp, (-0.28).sp), // Display
        headlineMedium = style(FontWeight.ExtraBold, 26, 32.sp, (-0.26).sp), // Screen title
        titleLarge = style(FontWeight.Bold, 22, 28.sp), // Title
        titleMedium = style(FontWeight.Bold, 17, 24.sp), // Headline
        titleSmall = style(FontWeight.Bold, 15), // Button label
        bodyLarge = style(FontWeight.Normal, 15, 22.sp), // Body
        bodyMedium = style(FontWeight.Normal, 14), // Secondary text and links
        bodySmall = style(FontWeight.SemiBold, 12), // Helper and error text
        labelLarge = style(FontWeight.SemiBold, 14, 20.sp), // Label
        labelMedium = style(FontWeight.SemiBold, 13), // Field label
        labelSmall = style(FontWeight.Medium, 12, 16.sp), // Caption
    )
