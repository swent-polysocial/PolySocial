// Contributors: Claude (wrote these tests for the shared loading and error states, #74; scrolling
// and heading after review; the spinner's Figma drawing); Claude (optional Log out link, #208).
package com.polysocial.ui.common

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class StateScreensTest {
  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun loadingState_showsItsMessageAndAnIndeterminateSpinner() {
    composeTestRule.setContent { PolySocialTheme { LoadingState("Loading your profile…") } }

    composeTestRule.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    composeTestRule.onNodeWithText("Loading your profile…").assertIsDisplayed()
    // Accessibility services announce the spinner as an ongoing progress indicator.
    composeTestRule
        .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
        .assertIsDisplayed()
  }

  @Test
  // Real drawing: the legacy graphics mode doesn't rasterize the Canvas.
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "xxhdpi")
  fun loadingState_spinnerDrawsFigmasQuarterArcOnAFaintRing() {
    // Paused at the first frame, so the arc hasn't turned yet: it runs from the top to the right.
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent { PolySocialTheme { LoadingState("Loading your profile…") } }

    val spinner =
        composeTestRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .captureToImage()
            .toPixelMap()
    val px = composeTestRule.density.density
    // How dark a pixel looks on a light background: 0 is nothing drawn, 1 is opaque black.
    fun darkness(xDp: Float, yDp: Float): Float {
      val color = spinner[(xDp * px).toInt(), (yDp * px).toInt()]
      return color.alpha * (1f - color.luminance())
    }
    // The ring's centre line is a 12.8 dp circle around the centre of the 32 dp box.
    val ringRadius = 12.8f
    val diagonal = ringRadius / sqrt(2f)

    // The quarter arc (top right) is solid Ink.
    assertTrue(darkness(16f + diagonal, 16f - diagonal) > 0.6f)
    // The rest of the ring (left, bottom) is Ink at 20 %: visible, but much lighter than the arc.
    listOf(darkness(16f - ringRadius, 16f), darkness(16f, 16f + ringRadius)).forEach {
      assertTrue("ring darkness $it", it in 0.05f..0.4f)
    }
    // Nothing is drawn in the middle.
    assertTrue(darkness(16f, 16f) < 0.05f)
  }

  @Test
  fun errorState_showsItsTextsAndRetryCallsBackOncePerTap() {
    var retries = 0
    composeTestRule.setContent {
      PolySocialTheme {
        ErrorState(
            title = "Couldn't load your profile",
            message = "Check your connection and try again.",
            onRetry = { retries++ },
        )
      }
    }

    composeTestRule.onNodeWithTag(C.Tag.error_state).assertIsDisplayed()
    composeTestRule
        .onNode(hasText("Couldn't load your profile") and isHeading())
        .assertIsDisplayed()
    composeTestRule.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.error_retry)
        .assertTextEquals(string(R.string.retry))
        .performClick()
    assertEquals(1, retries)
  }

  @Test
  fun errorState_scrollsSoRetryStaysReachableOnAShortScreenWithLargeText() {
    var retries = 0
    composeTestRule.setContent {
      PolySocialTheme {
        // Shorter than the content, like a phone in landscape with the largest text. (Robolectric
        // barely measures text, so the box is made short enough without it.)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
          Box(Modifier.size(width = 640.dp, height = 120.dp)) {
            ErrorState(
                title = "Couldn't load your profile",
                message = "Check your connection and try again.",
                onRetry = { retries++ },
            )
          }
        }
      }
    }

    composeTestRule.onNodeWithTag(C.Tag.error_retry).assertIsNotDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.error_retry).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.error_retry).performClick()
    assertEquals(1, retries)
  }

  @Test
  fun errorState_withoutASecondaryAction_showsNoLink() {
    composeTestRule.setContent {
      PolySocialTheme { ErrorState(title = "Couldn't load", message = "Try again.", onRetry = {}) }
    }

    composeTestRule.onNodeWithTag(C.Tag.error_retry).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.error_secondary).assertDoesNotExist()
  }

  @Test
  fun errorState_secondaryLink_sits10dpUnderRetry_andCallsBackOncePerTap() {
    var retries = 0
    var secondary = 0
    composeTestRule.setContent {
      PolySocialTheme {
        ErrorState(
            title = "Couldn't load your profile",
            message = "Check your connection and try again.",
            onRetry = { retries++ },
            secondaryLabel = "Log out",
            onSecondary = { secondary++ },
        )
      }
    }

    val link = composeTestRule.onNodeWithTag(C.Tag.error_secondary)
    link.assertIsDisplayed().assertTextEquals("Log out")
    // Figma "App start · couldn't load profile" (490:26): the stack's 10 dp spacing under Retry.
    val retry = composeTestRule.onNodeWithTag(C.Tag.error_retry).getUnclippedBoundsInRoot()
    assertEquals(10f, (link.getUnclippedBoundsInRoot().top - retry.bottom).value, 0.01f)
    link.performClick()
    assertEquals(1, secondary)
    assertEquals(0, retries)
  }

  private fun string(id: Int) =
      ApplicationProvider.getApplicationContext<Application>().getString(id)
}
