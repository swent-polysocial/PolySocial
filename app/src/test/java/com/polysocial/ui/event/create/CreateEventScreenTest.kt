// Contributors: Claude (wrote these tests).
package com.polysocial.ui.event.create

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.MAX_DESCRIPTION_LENGTH
import com.polysocial.model.event.MAX_TITLE_LENGTH
import com.polysocial.resources.C
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI tests for the Create Event screen (#46). The date and time pickers and the location picker
 * open dialogs or another screen, so the tests set those values through the ViewModel and drive
 * everything else through the UI.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CreateEventScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val repository = FakeEventRepository(now = FORM_NOW)
  private val viewModel = CreateEventViewModel(repository, Clock.fixed(FORM_NOW, ZURICH))

  private var closed = false
  private var locationRequested = false
  private var viewedEventId: String? = null
  private var backToMap = false

  @Before
  fun showScreen() {
    composeTestRule.setContent {
      CreateEventScreen(
          viewModel = viewModel,
          onClose = { closed = true },
          onPickLocation = { locationRequested = true },
          onViewEvent = { viewedEventId = it },
          onBackToMap = { backToMap = true },
      )
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun error(error: CreateEventFormError) = C.Tag.createEventError(error.name)

  private fun form() = viewModel.uiState.value.form

  /** Picks the category, date, start time and location, which open dialogs or another screen. */
  private fun pickDateTimeAndLocation(date: LocalDate = LocalDate.of(2026, 10, 10)) {
    composeTestRule.runOnIdle {
      viewModel.onCategorySelect(EventCategory.STUDY)
      viewModel.onDatePick(date)
      viewModel.onStartTimePick(LocalTime.of(14, 0))
      viewModel.onLocationPick(ROLEX)
    }
  }

  private fun submit() {
    node(C.Tag.create_event_submit).performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun aStudentCanCreateAPrivateEvent() {
    node(C.Tag.create_event_title).performTextInput("Study together")
    pickDateTimeAndLocation()

    node(C.Tag.create_event_submit).assertTextContains("Create private event")
    submit()

    node(C.Tag.event_created_screen).assertIsDisplayed()
    assertTrue(repository.events.single().isPrivate)
  }

  @Test
  fun aStudentCanCreateAPublicEvent() {
    node(C.Tag.create_event_title).performTextInput("Football on the lawn")
    pickDateTimeAndLocation()
    node(C.Tag.create_event_public).performScrollTo().performClick()

    node(C.Tag.create_event_submit).assertTextContains("Create public event")
    submit()

    node(C.Tag.event_created_screen).assertIsDisplayed()
    assertFalse(repository.events.single().isPrivate)
  }

  @Test
  fun aMissingTitle_blocksSubmitWithAnInlineError() {
    pickDateTimeAndLocation()

    submit()

    node(C.Tag.createEventError(CreateEventFormError.MISSING_TITLE.name))
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains("Add a title")
    node(C.Tag.create_event_submit).assertIsNotEnabled()
    assertTrue(repository.events.isEmpty())
  }

  @Test
  fun aPastDate_blocksSubmitWithAnInlineError() {
    node(C.Tag.create_event_title).performTextInput("Study together")
    pickDateTimeAndLocation(date = LocalDate.of(2026, 10, 6))

    submit()

    node(C.Tag.createEventError(CreateEventFormError.START_IN_PAST.name))
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains("Pick a date in the future")
    assertTrue(repository.events.isEmpty())
  }

  @Test
  fun submitting_callsCreateEventWithTheMappedFields() {
    node(C.Tag.create_event_title).performTextInput("Board games night")
    node(C.Tag.create_event_description).performTextInput("Bring a game")
    node(C.Tag.createEventCategory(EventCategory.PARTY.name)).performScrollTo().performClick()
    node(C.Tag.create_event_capacity).performScrollTo().performTextInput("12")
    composeTestRule.runOnIdle {
      viewModel.onDatePick(LocalDate.of(2026, 10, 10))
      viewModel.onStartTimePick(LocalTime.of(22, 0))
      viewModel.onEndTimePick(LocalTime.of(2, 0))
      viewModel.onLocationPick(ROLEX)
    }

    submit()

    val saved = repository.events.single()
    assertEquals("Board games night", saved.title)
    assertEquals("Bring a game", saved.description)
    assertEquals(EventCategory.PARTY, saved.category)
    assertEquals(ROLEX.coordinates, saved.location)
    assertEquals(Instant.parse("2026-10-10T20:00:00Z"), saved.startTime)
    assertEquals(Instant.parse("2026-10-11T00:00:00Z"), saved.endTime)
    assertEquals(12, saved.capacity)
    assertTrue(saved.isPrivate)
    assertEquals(repository.currentUid, saved.createdBy)
  }

  @Test
  fun anEndBeforeTheStart_showsPlusOneDay() {
    composeTestRule.runOnIdle {
      viewModel.onStartTimePick(LocalTime.of(22, 0))
      viewModel.onEndTimePick(LocalTime.of(2, 0))
    }

    node(C.Tag.create_event_end_next_day).performScrollTo().assertTextContains("+1 day")
  }

  @Test
  fun offline_showsTheBannerAndTryAgainWorks() {
    node(C.Tag.create_event_title).performTextInput("Study together")
    pickDateTimeAndLocation()
    repository.failure = CreateEventResult.NetworkError

    submit()

    node(C.Tag.create_event_failure).assertIsDisplayed()
    node(C.Tag.create_event_submit).assertTextContains("Try again").assertIsEnabled()

    repository.failure = null
    submit()

    node(C.Tag.event_created_screen).assertIsDisplayed()
  }

  @Test
  fun theConfirmation_opensTheEventOrGoesBackToTheMap() {
    node(C.Tag.create_event_title).performTextInput("Study together")
    pickDateTimeAndLocation()
    submit()

    node(C.Tag.event_created_view_event).performClick()
    node(C.Tag.event_created_back_to_map).performClick()

    assertEquals("event-1", viewedEventId)
    assertTrue(backToMap)
  }

  @Test
  fun visibility_canSwitchBackToPrivate() {
    node(C.Tag.create_event_public).performScrollTo().performClick()
    node(C.Tag.create_event_private).performScrollTo().performClick()

    node(C.Tag.create_event_submit).assertTextContains("Create private event")
  }

  @Test
  fun anUnexpectedFailure_showsTheGenericMessage() {
    node(C.Tag.create_event_title).performTextInput("Study together")
    pickDateTimeAndLocation()
    repository.failure = CreateEventResult.UnexpectedError

    submit()

    composeTestRule.onNodeWithText("Something went wrong", substring = true).assertIsDisplayed()
  }

  @Test
  fun submittingAnEmptyForm_showsTheCategoryDateAndLocationErrors() {
    submit()

    node(error(CreateEventFormError.MISSING_CATEGORY))
        .performScrollTo()
        .assertTextContains("Pick a category")

    node(error(CreateEventFormError.MISSING_DATE))
        .performScrollTo()
        .assertTextContains("Pick a date and a start time")
    node(error(CreateEventFormError.MISSING_LOCATION))
        .performScrollTo()
        .assertTextContains("Pick a location")
  }

  @Test
  fun tooLongTextAndASmallCapacity_showInlineErrors() {
    node(C.Tag.create_event_title).performTextInput("a".repeat(MAX_TITLE_LENGTH + 1))
    node(C.Tag.create_event_description).performTextInput("a".repeat(MAX_DESCRIPTION_LENGTH + 1))
    node(C.Tag.create_event_capacity).performScrollTo().performTextInput("1")

    node(error(CreateEventFormError.TITLE_TOO_LONG))
        .performScrollTo()
        .assertTextContains("Use at most 80 characters")
    node(error(CreateEventFormError.DESCRIPTION_TOO_LONG))
        .performScrollTo()
        .assertTextContains("Use at most 5000 characters")
    node(error(CreateEventFormError.CAPACITY_TOO_SMALL))
        .performScrollTo()
        .assertTextContains("Enter at least 2 people")
  }

  @Test
  fun aCapacityThatIsNotAWholeNumber_showsItsOwnError() {
    node(C.Tag.create_event_capacity).performScrollTo().performTextInput("2.5")

    node(error(CreateEventFormError.CAPACITY_NOT_A_NUMBER))
        .performScrollTo()
        .assertTextContains("Enter a whole number")
    node(error(CreateEventFormError.CAPACITY_TOO_SMALL)).assertDoesNotExist()
  }

  @Test
  fun anEndEqualToTheStart_showsAnInlineError() {
    pickDateTimeAndLocation()
    composeTestRule.runOnIdle { viewModel.onEndTimePick(LocalTime.of(14, 0)) }

    node(error(CreateEventFormError.END_SAME_AS_START))
        .performScrollTo()
        .assertTextContains("The end time must differ from the start time")
  }

  @Test
  fun theDateDialog_setsThePickedDate() {
    composeTestRule.runOnIdle { viewModel.onDatePick(LocalDate.of(2026, 10, 10)) }

    node(C.Tag.create_event_date).performScrollTo().performClick()
    composeTestRule
        .onAllNodes(hasText("October 15", substring = true) and hasClickAction())
        .onFirst()
        .performClick()
    node(C.Tag.create_event_dialog_confirm).performClick()

    composeTestRule.runOnIdle { assertEquals(LocalDate.of(2026, 10, 15), form().date) }
  }

  @Test
  fun theDateDialog_withoutASelection_justCloses() {
    node(C.Tag.create_event_date).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_confirm).performClick()

    node(C.Tag.create_event_dialog_confirm).assertDoesNotExist()
    composeTestRule.runOnIdle { assertNull(form().date) }
  }

  @Test
  fun theStartTimeDialog_setsTheStartTime() {
    node(C.Tag.create_event_start).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_confirm).performClick()

    composeTestRule.runOnIdle { assertEquals(LocalTime.of(18, 0), form().startTime) }
  }

  @Test
  fun theEndTimeDialog_setsTheEndTimeAndOffersNoClearWithoutOne() {
    node(C.Tag.create_event_end).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_clear).assertDoesNotExist()
    node(C.Tag.create_event_dialog_confirm).performClick()

    composeTestRule.runOnIdle { assertEquals(LocalTime.of(18, 0), form().endTime) }
  }

  @Test
  fun cancellingTheEndTimeDialog_keepsTheEndTime() {
    composeTestRule.runOnIdle { viewModel.onEndTimePick(LocalTime.of(20, 0)) }

    node(C.Tag.create_event_end).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_dismiss).assertTextContains("Cancel").performClick()

    node(C.Tag.create_event_dialog_dismiss).assertDoesNotExist()
    composeTestRule.runOnIdle { assertEquals(LocalTime.of(20, 0), form().endTime) }
  }

  @Test
  fun clearingTheEndTime_removesIt() {
    composeTestRule.runOnIdle { viewModel.onEndTimePick(LocalTime.of(20, 0)) }

    node(C.Tag.create_event_end).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_clear).assertTextContains("Clear end time").performClick()

    node(C.Tag.create_event_dialog_clear).assertDoesNotExist()
    composeTestRule.runOnIdle { assertNull(form().endTime) }
  }

  @Test
  fun cancellingTheDialogs_changesNothing() {
    node(C.Tag.create_event_date).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_dismiss).performClick()
    node(C.Tag.create_event_start).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_dismiss).assertTextContains("Cancel").performClick()

    node(C.Tag.create_event_dialog_dismiss).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertNull(form().date)
      assertNull(form().startTime)
    }
  }

  @Test
  fun closeAndLocation_callTheirCallbacks() {
    node(C.Tag.create_event_location).performScrollTo().performClick()
    node(C.Tag.create_event_close).performClick()

    assertTrue(locationRequested)
    assertTrue(closed)
  }
}
