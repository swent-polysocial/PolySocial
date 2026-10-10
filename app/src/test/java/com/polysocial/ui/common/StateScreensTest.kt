// Contributors: Claude (wrote these tests for the shared loading and error states, #74; scrolling
// and heading after review).
package com.polysocial.ui.common

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

  private fun string(id: Int) =
      ApplicationProvider.getApplicationContext<Application>().getString(id)
}
