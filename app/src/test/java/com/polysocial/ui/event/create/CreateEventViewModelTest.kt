// Contributors: Claude (wrote these tests); Mohamed Khellaf (reviewed).
package com.polysocial.ui.event.create

import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.MAX_DESCRIPTION_LENGTH
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateEventViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val repository = FakeEventRepository(now = FORM_NOW)
  private val viewModel = CreateEventViewModel(repository, Clock.fixed(FORM_NOW, ZURICH))

  private fun state() = viewModel.uiState.value

  private fun fillCompleteForm() {
    val form = completeForm()
    viewModel.onTitleChange(form.title)
    viewModel.onDescriptionChange(form.description)
    viewModel.onCategorySelect(form.category!!)
    viewModel.onDatePick(form.date!!)
    viewModel.onStartTimePick(form.startTime!!)
    viewModel.onEndTimePick(form.endTime)
    viewModel.onLocationPick(form.location!!)
    viewModel.onCapacityChange(form.capacityText)
  }

  @Test
  fun initialState_isAnEmptyPrivateFormWithNoCategoryAndNoErrors() {
    assertEquals(CreateEventForm(), state().form)
    assertEquals(LocalDate.of(2026, 10, 7), state().today)
    assertTrue(state().visibleErrors.isEmpty())
    assertTrue(state().canSubmit)
    assertEquals(CreateEventStatus.Editing, state().status)
  }

  @Test
  fun anError_showsOnlyOnceItsFieldWasChanged() {
    viewModel.onTitleChange("x")
    viewModel.onTitleChange("")

    assertEquals(setOf(CreateEventFormError.MISSING_TITLE), state().visibleErrors)
  }

  @Test
  fun aPastStartTime_showsItsErrorBeforeTheRestOfTheFormIsFilled() {
    viewModel.onDatePick(LocalDate.of(2026, 10, 7))
    viewModel.onStartTimePick(LocalTime.of(10, 0))

    assertEquals(setOf(CreateEventFormError.START_IN_PAST), state().visibleErrors)
  }

  @Test
  fun submittingAnIncompleteForm_showsEveryErrorAndSendsNothing() = runTest {
    viewModel.onSubmit()
    advanceUntilIdle()

    assertEquals(
        setOf(
            CreateEventFormError.MISSING_TITLE,
            CreateEventFormError.MISSING_CATEGORY,
            CreateEventFormError.MISSING_DATE,
            CreateEventFormError.MISSING_LOCATION,
        ),
        state().visibleErrors,
    )
    assertFalse(state().canSubmit)
    assertTrue(repository.events.isEmpty())
  }

  @Test
  fun fixingTheErrors_enablesSubmitAgain() {
    viewModel.onSubmit()
    fillCompleteForm()

    assertTrue(state().visibleErrors.isEmpty())
    assertTrue(state().canSubmit)
  }

  @Test
  fun submittingACompleteForm_createsTheMappedEventAndShowsTheConfirmation() = runTest {
    fillCompleteForm()
    viewModel.onCategorySelect(EventCategory.PARTY)
    viewModel.onVisibilityChange(false)

    viewModel.onSubmit()
    assertEquals(CreateEventStatus.Submitting, state().status)
    assertFalse(state().canSubmit)
    advanceUntilIdle()

    val saved = repository.events.single()
    assertEquals("Study together", saved.title)
    assertEquals("Exercise sheet 4", saved.description)
    assertEquals(EventCategory.PARTY, saved.category)
    assertEquals(ROLEX.coordinates, saved.location)
    assertEquals(Instant.parse("2026-10-10T12:00:00Z"), saved.startTime)
    assertEquals(Instant.parse("2026-10-10T14:00:00Z"), saved.endTime)
    assertEquals(8, saved.capacity)
    assertFalse(saved.isPrivate)
    assertEquals(
        CreateEventStatus.Created("event-1", "Study together", isPrivate = false),
        state().status,
    )
  }

  @Test
  fun descriptionAndCapacityErrors_showOnceThoseFieldsChange() {
    viewModel.onDescriptionChange("a".repeat(MAX_DESCRIPTION_LENGTH + 1))
    viewModel.onCapacityChange("1")

    assertEquals(
        setOf(CreateEventFormError.DESCRIPTION_TOO_LONG, CreateEventFormError.INVALID_CAPACITY),
        state().visibleErrors,
    )
  }

  @Test
  fun aSecondSubmitWhileSaving_isIgnored() = runTest {
    fillCompleteForm()

    viewModel.onSubmit()
    viewModel.onSubmit()
    advanceUntilIdle()
    viewModel.onSubmit()
    advanceUntilIdle()

    assertEquals(1, repository.events.size)
  }

  @Test
  fun editsAreIgnoredWhileSaving() = runTest {
    fillCompleteForm()
    viewModel.onSubmit()

    viewModel.onTitleChange("Changed")
    advanceUntilIdle()

    assertEquals("Study together", repository.events.single().title)
  }

  @Test
  fun offline_keepsTheFormAndLetsTheOrganizerTryAgain() = runTest {
    fillCompleteForm()
    repository.failure = CreateEventResult.NetworkError

    viewModel.onSubmit()
    advanceUntilIdle()

    assertEquals(
        CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.OFFLINE),
        state().status,
    )
    assertEquals(completeForm(), state().form)
    assertTrue(state().canSubmit)

    repository.failure = null
    viewModel.onSubmit()
    advanceUntilIdle()

    assertTrue(state().status is CreateEventStatus.Created)
  }

  @Test
  fun otherFailures_showTheUnexpectedError() = runTest {
    fillCompleteForm()
    repository.failure = CreateEventResult.UnexpectedError

    viewModel.onSubmit()
    advanceUntilIdle()

    assertEquals(
        CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.UNEXPECTED),
        state().status,
    )
  }

  @Test
  fun editingAfterAFailure_goesBackToEditing() = runTest {
    fillCompleteForm()
    repository.failure = CreateEventResult.NetworkError
    viewModel.onSubmit()
    advanceUntilIdle()

    viewModel.onTitleChange("New title")

    assertEquals(CreateEventStatus.Editing, state().status)
  }

  @Test
  fun aRejectionFromTheRepository_returnsToTheForm() = runTest {
    fillCompleteForm()
    repository.now = Instant.parse("2026-10-11T00:00:00Z")

    viewModel.onSubmit()
    advanceUntilIdle()

    assertEquals(CreateEventStatus.Editing, state().status)
    assertTrue(repository.events.isEmpty())
  }

  @Test
  fun anEndBeforeTheStart_isSavedOnTheNextDay() = runTest {
    fillCompleteForm()
    viewModel.onStartTimePick(LocalTime.of(22, 0))
    viewModel.onEndTimePick(LocalTime.of(2, 0))

    assertTrue(state().form.endsNextDay)
    viewModel.onSubmit()
    advanceUntilIdle()

    assertEquals(Instant.parse("2026-10-11T00:00:00Z"), repository.events.single().endTime)
  }
}
