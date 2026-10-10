// Contributors: Claude (wrote these tests for the shared loading and error states, #74).
package com.polysocial.ui.common

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    composeTestRule.onNodeWithText("Couldn't load your profile").assertIsDisplayed()
    composeTestRule.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.error_retry).assertTextEquals("Retry").performClick()
    assertEquals(1, retries)
  }
}
