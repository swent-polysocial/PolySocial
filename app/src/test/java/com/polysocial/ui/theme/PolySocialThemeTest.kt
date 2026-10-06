// Contributors: Claude Opus 5.5 (wrote these tests, incl. container roles and field shape).
package com.polysocial.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that [PolySocialTheme] provides the v2 design-system tokens from Figma. Robolectric runs
 * on API 34, where dynamic colour would replace the colours if it were on.
 */
@RunWith(AndroidJUnit4::class)
class PolySocialThemeTest {
  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private lateinit var shapes: Shapes

  @Before
  fun readTheme() {
    composeTestRule.setContent {
      PolySocialTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        shapes = MaterialTheme.shapes
      }
    }
    composeTestRule.waitForIdle()
  }

  private fun allStyles(): Map<String, TextStyle> =
      with(typography) {
        mapOf(
            "displayLarge" to displayLarge,
            "displayMedium" to displayMedium,
            "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge,
            "headlineMedium" to headlineMedium,
            "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge,
            "titleMedium" to titleMedium,
            "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge,
            "bodyMedium" to bodyMedium,
            "bodySmall" to bodySmall,
            "labelLarge" to labelLarge,
            "labelMedium" to labelMedium,
            "labelSmall" to labelSmall,
        )
      }

  private fun assertStyle(
      style: TextStyle,
      weight: FontWeight,
      size: TextUnit,
      lineHeight: TextUnit = TextUnit.Unspecified,
      letterSpacing: TextUnit = 0.sp,
  ) {
    assertEquals(weight, style.fontWeight)
    assertEquals(size, style.fontSize)
    assertEquals(lineHeight, style.lineHeight)
    assertEquals(letterSpacing, style.letterSpacing)
  }

  @Test
  fun colorTokens_haveTheV2Values() {
    assertEquals(Color(0xFFFFFFFF), Bg)
    assertEquals(Color(0xFFF6F5F2), Surface)
    assertEquals(Color(0xFFE4E2DC), Border)
    assertEquals(Color(0xFFEFEDE8), Disabled)
    assertEquals(Color(0xFF17171C), Ink)
    assertEquals(Color(0xFF4A4A55), Ink2)
    assertEquals(Color(0xFF6B6B76), Ink3)
    assertEquals(Color(0xFFD6303A), Accent)
    assertEquals(Color(0xFFFDECEC), AccentSoft)
    assertEquals(Color(0xFFB3202A), AccentText)
    assertEquals(Color(0xFFFFB3B7), AccentOnInk)
    assertEquals(Color(0xFF1A7046), Success)
    assertEquals(Color(0xFFE3F4EA), SuccessSoft)
    assertEquals(Color(0xFF2F5FD0), Info)
    assertEquals(Color(0xFFE8EEFC), InfoSoft)
    assertEquals(Color(0xFF8A5000), Warning)
    assertEquals(Color(0xFFFDF1DC), WarningSoft)
    assertEquals(Color(0xFFEEF0EA), MapLand)
    assertEquals(Color(0xFFD6E8CF), MapPark)
    assertEquals(Color(0xFFCFE0F2), MapWater)
    assertEquals(Color(0xFFFFFFFF), MapRoad)
  }

  @Test
  fun colorScheme_mapsTheV2ColorsWithoutDynamicColor() {
    assertEquals(Ink, colors.primary)
    assertEquals(Bg, colors.onPrimary)
    assertEquals(Ink, colors.primaryContainer)
    assertEquals(Bg, colors.onPrimaryContainer)
    assertEquals(Accent, colors.secondary)
    assertEquals(Bg, colors.onSecondary)
    assertEquals(AccentSoft, colors.secondaryContainer)
    assertEquals(AccentText, colors.onSecondaryContainer)
    assertEquals(Info, colors.tertiary)
    assertEquals(Bg, colors.onTertiary)
    assertEquals(InfoSoft, colors.tertiaryContainer)
    assertEquals(Info, colors.onTertiaryContainer)
    assertEquals(Bg, colors.background)
    assertEquals(Ink, colors.onBackground)
    assertEquals(Bg, colors.surface)
    assertEquals(Ink, colors.onSurface)
    assertEquals(Surface, colors.surfaceVariant)
    assertEquals(Ink2, colors.onSurfaceVariant)
    assertEquals(Bg, colors.surfaceTint)
    assertEquals(Bg, colors.surfaceBright)
    assertEquals(Bg, colors.surfaceContainerLowest)
    assertEquals(Bg, colors.surfaceContainerLow)
    assertEquals(Bg, colors.surfaceContainer)
    assertEquals(Bg, colors.surfaceContainerHigh)
    assertEquals(Bg, colors.surfaceContainerHighest)
    assertEquals(Ink, colors.inverseSurface)
    assertEquals(Bg, colors.inverseOnSurface)
    assertEquals(AccentOnInk, colors.inversePrimary)
    assertEquals(Border, colors.outline)
    assertEquals(Border, colors.outlineVariant)
    assertEquals(Accent, colors.error)
    assertEquals(Bg, colors.onError)
    assertEquals(AccentSoft, colors.errorContainer)
    assertEquals(AccentText, colors.onErrorContainer)
  }

  @Test
  fun everyTextStyle_usesPlusJakartaSans() {
    val otherFont = allStyles().filterValues { it.fontFamily != PlusJakartaSans }.keys

    assertEquals("Text styles not using Plus Jakarta Sans", emptySet<String>(), otherFont)
  }

  @Test
  fun typeScale_matchesTheV2Sheet() {
    with(typography) {
      assertStyle(displaySmall, FontWeight.ExtraBold, 28.sp, 34.sp, (-0.28).sp) // Display
      assertStyle(headlineMedium, FontWeight.ExtraBold, 26.sp, 32.sp, (-0.26).sp) // Screen title
      assertStyle(titleLarge, FontWeight.Bold, 22.sp, 28.sp) // Title
      assertStyle(titleMedium, FontWeight.Bold, 17.sp, 24.sp) // Headline
      assertStyle(titleSmall, FontWeight.Bold, 15.sp) // Button label
      assertStyle(bodyLarge, FontWeight.Normal, 15.sp, 22.sp) // Body
      assertStyle(bodyMedium, FontWeight.Normal, 14.sp) // Secondary text and links
      assertStyle(bodySmall, FontWeight.SemiBold, 12.sp) // Helper and error text
      assertStyle(labelLarge, FontWeight.SemiBold, 14.sp, 20.sp) // Label
      assertStyle(labelMedium, FontWeight.SemiBold, 13.sp) // Field label
      assertStyle(labelSmall, FontWeight.Medium, 12.sp, 16.sp) // Caption
    }
  }

  @Test
  fun shapes_useTheV2Radii() {
    assertEquals(RoundedCornerShape(12.dp), shapes.small)
    assertEquals(RoundedCornerShape(16.dp), shapes.medium)
    assertEquals(RoundedCornerShape(26.dp), shapes.large)
  }
}
