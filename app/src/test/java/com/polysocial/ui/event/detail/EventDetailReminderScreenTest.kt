// Contributors: Claude Opus 5.5 (wrote these tests of the "Remind me" toggle on the event detail
// screen, second PR of #123).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.validEvent
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Remind me 1 h before" and its permission explanation/denied states (second PR of #123), from the
 * Figma "Event detail · registered" (558:2), "Reminder · permission explanation" (558:119) and
 * "Reminder · notifications denied" (558:186) frames.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class EventDetailReminderScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private val event =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
          )
          .copy(id = EVENT_ID, createdBy = "organizer", organizerIds = listOf("organizer"))

  private fun loaded(
      attendance: Attendance = Attendance.GOING,
      isOrganizer: Boolean = false,
      reminderOn: Boolean = false,
      remindersBlocked: Boolean = false,
      showReminderExplanation: Boolean = false,
  ) =
      EventDetailUiState.Loaded(
          EventDetails(
              event,
              LocalDate.of(2026, 10, 10),
              LocalTime.of(13, 0),
              LocalTime.of(17, 0),
              0,
          ),
          isOffline = false,
          isOrganizer = isOrganizer,
          attendance = attendance,
          reminderOn = reminderOn,
          remindersBlocked = remindersBlocked,
          showReminderExplanation = showReminderExplanation,
      )

  private val calls = mutableListOf<String>()

  private val actions =
      EventAttendanceActions(
          onReminderSwitchChange = { calls += "reminder $it" },
          onReminderExplanationContinue = { calls += "continue" },
          onReminderExplanationDismiss = { calls += "notNow" },
          onOpenNotificationSettings = { calls += "openSettings" },
      )

  private fun show(state: EventDetailUiState.Loaded) {
    composeTestRule.setContent {
      PolySocialTheme { EventDetailScreen(state, onBack = {}, onRetry = {}, attendance = actions) }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  @Test
  fun notGoing_hasNoReminderRow() {
    show(loaded(attendance = Attendance.NOT_GOING))

    node(EventDetailTags.REMINDER_ROW).assertDoesNotExist()
  }

  @Test
  fun anOrganizer_hasNoReminderRow() {
    show(loaded(isOrganizer = true, attendance = Attendance.NOT_GOING))

    node(EventDetailTags.REMINDER_ROW).assertDoesNotExist()
  }

  @Test
  fun going_showsTheReminderRow_off() {
    show(loaded())

    node(EventDetailTags.REMINDER_ROW).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.reminder_toggle_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.reminder_toggle_subtitle)).assertIsDisplayed()
    node(EventDetailTags.REMINDER_SWITCH).assertIsOff()
  }

  @Test
  fun reminderOn_showsTheSwitchOn() {
    show(loaded(reminderOn = true))

    node(EventDetailTags.REMINDER_SWITCH).assertIsOn()
  }

  @Test
  fun tappingTheSwitch_callsTheCallbackWithTheNewValue() {
    show(loaded(reminderOn = false))

    node(EventDetailTags.REMINDER_SWITCH).performClick()

    assertEquals(listOf("reminder true"), calls)
  }

  @Test
  fun blocked_showsTheBlockedSubtitle_andTheDeniedNote() {
    show(loaded(remindersBlocked = true))

    composeTestRule
        .onNodeWithText(string(R.string.reminder_toggle_subtitle_blocked))
        .assertIsDisplayed()
    node(EventDetailTags.REMINDER_DENIED_NOTE).assertIsDisplayed()

    node(EventDetailTags.REMINDER_OPEN_SETTINGS).performClick()
    assertEquals(listOf("openSettings"), calls)
  }

  @Test
  fun notBlocked_hasNoDeniedNote() {
    show(loaded())

    node(EventDetailTags.REMINDER_DENIED_NOTE).assertDoesNotExist()
  }

  @Test
  fun theExplanationSheet_showsWhenAsked() {
    show(loaded(showReminderExplanation = true))

    node(EventDetailTags.REMINDER_EXPLANATION_SHEET).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.reminder_explanation_title)).assertIsDisplayed()
  }

  @Test
  fun theExplanationSheet_continueCallsContinue() {
    show(loaded(showReminderExplanation = true))

    node(EventDetailTags.REMINDER_EXPLANATION_CONTINUE).performClick()

    assertEquals(listOf("continue"), calls)
  }

  @Test
  fun theExplanationSheet_notNowCallsDismiss() {
    show(loaded(showReminderExplanation = true))

    node(EventDetailTags.REMINDER_EXPLANATION_NOT_NOW).performClick()
    // dismiss() slides the sheet away (a coroutine) before calling onDismissRequest.
    composeTestRule.waitForIdle()

    assertEquals(listOf("notNow"), calls)
  }

  @Test
  fun hidden_hasNoExplanationSheet() {
    show(loaded())

    node(EventDetailTags.REMINDER_EXPLANATION_SHEET).assertDoesNotExist()
  }

  private companion object {
    const val EVENT_ID = "event-1"
  }
}
