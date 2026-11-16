// Contributors: Claude (Sonnet 5.5, Anthropic) wrote these tests of the "Cancel your registration?"
// sheet from the Figma "Event detail · cancel registration (in a group)" frame, #318.
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CancelRegistrationSheetTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val calls = mutableListOf<String>()

  private fun show(state: CancelRegistrationState) {
    composeTestRule.setContent {
      PolySocialTheme {
        CancelRegistrationSheet(
            title = "Balélec warm-up",
            state = state,
            onConfirm = { calls += "confirm" },
            onKeep = { calls += "keep" },
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  @Test
  fun confirming_saysItAlsoLeavesTheGroup_namingTheEvent() {
    show(CancelRegistrationState.Confirming)

    node(EventDetailTags.CANCEL_REGISTRATION_SHEET).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.cancel_registration_title))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.cancel_registration_body, "Balélec warm-up"))
        .assertIsDisplayed()
    node(EventDetailTags.CANCEL_REGISTRATION_FAILURE).assertDoesNotExist()
  }

  @Test
  fun theRedButtonConfirms() {
    show(CancelRegistrationState.Confirming)

    node(EventDetailTags.CONFIRM_CANCEL_REGISTRATION).assertIsEnabled().performClick()

    assertEquals(listOf("confirm"), calls)
  }

  @Test
  fun keepGoingKeeps() {
    show(CancelRegistrationState.Confirming)

    node(EventDetailTags.KEEP_REGISTRATION).assertIsEnabled().performClick()
    // Closing slides the sheet away before it reports.
    composeTestRule.waitForIdle()

    assertEquals(listOf("keep"), calls)
  }

  @Test
  fun whileLeaving_nothingCanBeTapped() {
    show(CancelRegistrationState.Leaving)

    composeTestRule
        .onNodeWithText(context.getString(R.string.cancel_registration_leaving))
        .assertIsDisplayed()
    node(EventDetailTags.CONFIRM_CANCEL_REGISTRATION).assertIsNotEnabled()
    node(EventDetailTags.KEEP_REGISTRATION).assertIsNotEnabled()
  }

  @Test
  fun anOfflineFailure_saysNothingChanged_andOffersTryAgain() {
    show(CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.OFFLINE))

    composeTestRule
        .onNodeWithText(context.getString(R.string.cancel_registration_offline))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.create_event_try_again))
        .performClick()
    assertEquals(listOf("confirm"), calls)
  }

  @Test
  fun anUnexpectedFailure_hasItsOwnReason() {
    show(CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.UNEXPECTED))

    composeTestRule
        .onNodeWithText(context.getString(R.string.cancel_registration_unexpected))
        .assertIsDisplayed()
  }
}
