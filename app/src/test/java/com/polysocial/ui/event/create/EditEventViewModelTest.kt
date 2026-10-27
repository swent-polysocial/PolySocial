// Contributors: Claude (wrote these tests of Edit event, #131; the repository's errors, #131
// review).
package com.polysocial.ui.event.create

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.event.EventValidationError
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.UpdateEventResult
import com.polysocial.model.event.withCreator
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [CreateEventViewModel] editing an existing event (#131), against a [FakeEventRepository]. */
class EditEventViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val repository = FakeEventRepository(currentUid = "organizer", now = FORM_NOW)

  /** The event being edited: [completeForm]'s, organized by the signed-in user. */
  private val stored =
      completeForm()
          .toEvent(ZURICH)!!
          .withCreator("organizer", isVerifiedAssociation = false)
          .copy(id = "event-1")

  private fun editing(id: String = "event-1") =
      CreateEventViewModel(
          repository,
          Clock.fixed(FORM_NOW, ZURICH),
          SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to id)),
      )

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  private fun CreateEventViewModel.state() = uiState.value

  @Test
  fun theForm_isFilledInWithTheEventOnceItIsRead() {
    repository.seed(stored)
    val viewModel = editing()

    assertEquals(CreateEventStatus.Loading, viewModel.state().status)
    assertTrue(viewModel.state().isEditing)
    // Nothing can be changed or sent before the form is filled in.
    viewModel.onTitleChange("Too early")
    viewModel.onSubmit()
    runPending()

    assertEquals(completeForm(), viewModel.state().form)
    assertEquals(CreateEventStatus.Editing, viewModel.state().status)
    assertTrue(viewModel.state().visibleErrors.isEmpty())
    assertTrue(viewModel.state().canSubmit)
    assertTrue(repository.updateRequests.isEmpty())
  }

  @Test
  fun anEventThatCannotBeRead_isUnavailable() {
    // Deleted (or not allowed), then offline and never read.
    assertEquals(
        CreateEventStatus.Unavailable,
        editing("missing").also { runPending() }.state().status,
    )

    repository.readFromCache = true
    assertEquals(CreateEventStatus.Unavailable, editing().also { runPending() }.state().status)
  }

  @Test
  fun saving_sendsTheChangesToTheEventAndIsSaved() {
    repository.seed(stored)
    val viewModel = editing()
    runPending()

    viewModel.onTitleChange("Exam review")
    viewModel.onSubmit()
    assertEquals(CreateEventStatus.Saving, viewModel.state().status)
    runPending()

    assertEquals(CreateEventStatus.Saved, viewModel.state().status)
    // Once saved, the form is closing: nothing changes or is sent again.
    viewModel.onTitleChange("Too late")
    viewModel.onSubmit()
    runPending()
    assertEquals("Exam review", viewModel.state().form.title)
    assertEquals("event-1", repository.updateRequests.single().id)
    assertEquals("Exam review", repository.events.single().title)
    assertEquals(stored.startTime, repository.events.single().startTime)
  }

  @Test
  fun anEventThatHasStarted_canBeEditedButNotMovedIntoThePast() {
    // Wednesday 7 October, 11:00: an hour before FORM_NOW.
    val started =
        completeForm()
            .copy(date = LocalDate.of(2026, 10, 7), startTime = LocalTime.of(11, 0))
            .toEvent(ZURICH)!!
            .withCreator("organizer", isVerifiedAssociation = false)
            .copy(id = "event-1")
    repository.seed(started)
    val viewModel = editing()
    runPending()

    viewModel.onStartTimePick(LocalTime.of(10, 0))
    assertEquals(setOf(CreateEventFormError.START_IN_PAST), viewModel.state().visibleErrors)

    viewModel.onStartTimePick(LocalTime.of(11, 0))
    viewModel.onTitleChange("Study together, room CE 1")
    viewModel.onSubmit()
    runPending()

    assertEquals(CreateEventStatus.Saved, viewModel.state().status)
    assertEquals("Study together, room CE 1", repository.events.single().title)
  }

  @Test
  fun savingOffline_keepsTheChangesAndOffersTryAgain() {
    repository.seed(stored)
    val viewModel = editing()
    runPending()
    repository.updateFailure = UpdateEventResult.NetworkError

    viewModel.onTitleChange("Exam review")
    viewModel.onSubmit()
    runPending()

    assertEquals(
        CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.OFFLINE),
        viewModel.state().status,
    )
    assertEquals("Exam review", viewModel.state().form.title)
    assertTrue(viewModel.state().canSubmit)
    assertEquals(stored, repository.events.single())
  }

  @Test
  fun aSaveTheRepositoryRefuses_isNeverReportedAsSaved() {
    repository.seed(stored)
    for (refusal in
        listOf(
            UpdateEventResult.NotOrganizer,
            UpdateEventResult.NotFound,
            UpdateEventResult.UnexpectedError,
        )) {
      val viewModel = editing()
      runPending()
      repository.updateFailure = refusal

      viewModel.onSubmit()
      runPending()

      assertEquals(
          "$refusal",
          CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.UNEXPECTED),
          viewModel.state().status,
      )
    }
  }

  @Test
  fun anUpdateTheRepositoryFindsInvalid_showsTheErrors() {
    repository.seed(stored)
    val viewModel = editing()
    runPending()
    repository.updateFailure = UpdateEventResult.Invalid(listOf(EventValidationError.START_IN_PAST))

    viewModel.onSubmit()
    runPending()

    // The form found nothing wrong, so only the repository's error explains the refusal.
    assertEquals(CreateEventStatus.Editing, viewModel.state().status)
    assertEquals(setOf(CreateEventFormError.START_IN_PAST), viewModel.state().visibleErrors)
    assertFalse(viewModel.state().canSubmit)
    assertEquals(stored, repository.events.single())

    // Changing the form hides it until the next save.
    viewModel.onTitleChange("Exam review")
    assertTrue(viewModel.state().visibleErrors.isEmpty())
    assertTrue(viewModel.state().canSubmit)
  }
}
