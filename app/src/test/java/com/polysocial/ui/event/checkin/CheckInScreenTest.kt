// Contributors: OpenAI Codex (GPT-6.2 Sol, high; tested the Figma states for #469 under Lucas Pinto
// Ricardo’s supervision and approval).
package com.polysocial.ui.event.checkin

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.validEvent
import com.polysocial.ui.event.checkin.CheckInState.Stage
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class CheckInScreenTest {
  @get:Rule val compose = createComposeRule()

  private val start = Instant.parse("2026-11-25T20:00:00Z")
  private val event =
      validEvent(title = "Balélec warm-up", startTime = start)
          .copy(id = "venue", locationName = "Satellite")
  private val open =
      CheckInEntry(event, CheckInTarget(event, "student"), CheckInAvailability.OPEN, ZoneOffset.UTC)

  @Test
  fun locationFailuresShowTheSpecificReasonWithTryAgainAndTheEntranceQrAction() {
    val state = mutableStateOf<CheckInState>(Stage.READY)
    var retries = 0
    var settings = 0
    compose.setContent {
      PolySocialTheme {
        CheckInScreen(
            open,
            state.value,
            {},
            { retries++ },
            {
              settings++
              false
            },
            null,
        )
      }
    }
    compose.onNodeWithText("One-time location check").assertIsDisplayed()
    compose.onNodeWithTag(CheckInTags.LOCATION).performClick()
    val failures =
        listOf(
            Stage.DENIED to "Location access is off",
            Stage.APPROXIMATE_ONLY to "Precise location is off",
            Stage.TOO_FAR to "You’re too far from the venue",
            Stage.NO_FIX to "Couldn’t find your location",
            Stage.TIMED_OUT to "Location check timed out",
        )
    for ((failure, title) in failures) {
      compose.runOnIdle { state.value = failure }
      compose.onNodeWithText(title).assertIsDisplayed()
      compose.onNodeWithTag(CheckInTags.QR).assertIsDisplayed()
      compose.onNodeWithTag(CheckInTags.RETRY).performClick()
    }
    assertEquals(6, retries)

    compose.runOnIdle { state.value = Stage.APPROXIMATE_ONLY }
    compose.onNodeWithTag(CheckInTags.SETTINGS).performClick()
    compose.onNodeWithText("Couldn’t open Settings.").assertIsDisplayed()
    assertEquals(1, settings)
    compose.onNodeWithTag(CheckInTags.QR).performClick()
    compose.onNodeWithText("Entrance QR scanning isn’t available yet.").assertIsDisplayed()
  }

  @Test
  fun beforeAndAfterTheWindowShowTimesAndDoNotOfferLocationUnlessOpen() {
    val entry = mutableStateOf(open.copy(availability = CheckInAvailability.NOT_OPEN))
    val state = mutableStateOf<CheckInState>(Stage.READY)
    var back = 0
    var group = 0
    compose.setContent {
      PolySocialTheme {
        CheckInScreen(entry.value, state.value, { back++ }, {}, { true }, { group++ })
      }
    }
    compose.onNodeWithText("Check-in opens at 19:30").assertIsDisplayed()
    compose.onNodeWithTag(CheckInTags.LOCATION).assertDoesNotExist()
    compose.runOnIdle { entry.value = open }
    compose.onNodeWithTag(CheckInTags.LOCATION).assertIsDisplayed()
    compose.runOnIdle { entry.value = open.copy(availability = CheckInAvailability.CLOSED) }
    compose.onNodeWithText("Check-in has closed").assertIsDisplayed()
    compose.onNodeWithTag(CheckInTags.LOCATION).assertDoesNotExist()
    compose.onNodeWithText("Back to event").performClick()
    assertEquals(1, back)

    compose.runOnIdle { state.value = CheckInState.Confirmed(start.plusSeconds(480)) }
    compose
        .onNodeWithText("Checked in at 20:08. Your group can see that you’ve arrived.")
        .assertIsDisplayed()
    compose.onNodeWithTag(CheckInTags.GROUP).performClick()
    assertEquals(1, group)
  }

  @Test
  fun progressFailuresAndConfirmationWithoutAGroupRenderTheCorrectActions() {
    val state = mutableStateOf<CheckInState>(Stage.LOADING)
    var retries = 0
    compose.setContent {
      PolySocialTheme { CheckInScreen(open, state.value, {}, { retries++ }, { true }, null) }
    }
    for ((stage, text) in
        listOf(
            Stage.LOADING to "Loading check-in…",
            Stage.AWAITING_PERMISSION to "Checking your location…",
            Stage.CHECKING_LOCATION to "Checking your location…",
            Stage.SAVING to "Confirming your check-in…",
        )) {
      compose.runOnIdle { state.value = stage }
      compose.onNodeWithText(text).assertIsDisplayed()
      compose.onNodeWithTag(CheckInTags.LOCATION).assertDoesNotExist()
    }
    for ((stage, text) in
        listOf(
            Stage.OFFLINE to "You’re offline",
            Stage.STATUS_ERROR to "Couldn’t load your check-in",
            Stage.SAVE_ERROR to "Couldn’t confirm your check-in",
            Stage.WINDOW_CHANGED to "The check-in window changed",
        )) {
      compose.runOnIdle { state.value = stage }
      compose.onNodeWithText(text).assertIsDisplayed()
      compose.onNodeWithTag(CheckInTags.RETRY).performClick()
    }
    for ((stage, text) in
        listOf(
            Stage.EVENT_UNAVAILABLE to "Event unavailable",
            Stage.REGISTRATION_REQUIRED to "Registration required",
        )) {
      compose.runOnIdle { state.value = stage }
      compose.onNodeWithText(text).assertIsDisplayed()
      compose.onNodeWithText("Back to event").assertIsDisplayed()
      compose.onNodeWithTag(CheckInTags.RETRY).assertDoesNotExist()
    }
    compose.runOnIdle { state.value = CheckInState.Confirmed(start) }
    compose.onNodeWithText("Checked in at 20:00.").assertIsDisplayed()
    compose.onNodeWithTag(CheckInTags.GROUP).assertDoesNotExist()
    assertEquals(4, retries)
  }
}
