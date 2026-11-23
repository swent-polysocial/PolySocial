// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the sections an event is open
// to, on the form and the ViewModel, #369).
package com.polysocial.ui.event.create

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.withCreator
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Who can see a public event, by section (#369): the form's choice and what is saved. */
class EventSectionsTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val repository = FakeEventRepository(currentUid = "organizer", now = FORM_NOW)

  private fun viewModel(arguments: Map<String, Any> = emptyMap()) =
      CreateEventViewModel(
          repository,
          Clock.fixed(FORM_NOW, ZURICH),
          SavedStateHandle(arguments),
          FakeAuthRepository(),
          FakeUserProfileRepository(),
          FakeAssociationRepository(),
          FakeRegistrationRepository(),
          FakePublicProfileRepository(),
      )

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  private fun CreateEventViewModel.fillCompleteForm() {
    val form = completeForm()
    onTitleChange(form.title)
    onDescriptionChange(form.description)
    onCategorySelect(form.category!!)
    onDatePick(form.date!!)
    onStartTimePick(form.startTime!!)
    onEndTimePick(form.endTime)
    onLocationPick(form.location!!)
    onCapacityChange(form.capacityText)
  }

  // The form

  @Test
  fun aPublicEvent_isBuiltWithTheSectionsInTheOrderOfTheSectionList() {
    val form = completeForm().copy(isPrivate = false, allowedSections = setOf("SC", "IN"))

    assertEquals(listOf("IN", "SC"), form.toEvent(ZURICH)!!.allowedSections)
  }

  @Test
  fun aPrivateEvent_isBuiltOpenToEverySection_whateverWasPicked() {
    val form = completeForm().copy(isPrivate = true, allowedSections = setOf("IN"))

    assertTrue(form.toEvent(ZURICH)!!.allowedSections.isEmpty())
  }

  @Test
  fun anEventToEdit_fillsTheFormWithItsSections() {
    val event =
        completeForm()
            .copy(isPrivate = false, allowedSections = setOf("IN", "SC"))
            .toEvent(ZURICH)!!

    assertEquals(setOf("IN", "SC"), event.toForm(ZURICH).allowedSections)
  }

  // The ViewModel

  @Test
  fun aSectionIsPickedAndUnpicked_andNoneMeansEverySection() {
    val viewModel = viewModel()

    viewModel.onSectionToggle("IN")
    viewModel.onSectionToggle("SC")
    assertEquals(setOf("IN", "SC"), viewModel.uiState.value.form.allowedSections)

    viewModel.onSectionToggle("IN")
    viewModel.onSectionToggle("SC")
    assertTrue(viewModel.uiState.value.form.allowedSections.isEmpty())
  }

  @Test
  fun aCodeTheAppDoesNotOffer_isIgnored() {
    val viewModel = viewModel()

    viewModel.onSectionToggle("XX")
    viewModel.onSectionToggle("in")

    assertTrue(viewModel.uiState.value.form.allowedSections.isEmpty())
  }

  @Test
  fun creatingAPublicEvent_savesItsSections() {
    val viewModel = viewModel()
    viewModel.fillCompleteForm()
    viewModel.onVisibilityChange(false)
    viewModel.onSectionToggle("SC")
    viewModel.onSectionToggle("IN")

    viewModel.onSubmit()
    runPending()

    assertEquals(listOf("IN", "SC"), repository.events.single().allowedSections)
  }

  @Test
  fun creatingAPrivateEvent_savesItOpenToEverySection_evenAfterSectionsWerePicked() {
    val viewModel = viewModel()
    viewModel.fillCompleteForm()
    viewModel.onVisibilityChange(false)
    viewModel.onSectionToggle("IN")
    viewModel.onVisibilityChange(true)

    viewModel.onSubmit()
    runPending()

    assertTrue(repository.events.single().allowedSections.isEmpty())
  }

  @Test
  fun makingAnEventPublicAgain_bringsBackTheSectionsThatWerePicked() {
    val viewModel = viewModel()
    viewModel.onVisibilityChange(false)
    viewModel.onSectionToggle("MA")

    viewModel.onVisibilityChange(true)
    viewModel.onVisibilityChange(false)

    assertEquals(setOf("MA"), viewModel.uiState.value.form.allowedSections)
  }

  @Test
  fun editing_startsFromTheStoredSections_andSavesTheNewChoice() {
    val stored =
        completeForm()
            .copy(isPrivate = false, allowedSections = setOf("IN", "SC"))
            .toEvent(ZURICH)!!
            .withCreator("organizer", isVerifiedAssociation = false)
            .copy(id = "event-1")
    repository.seed(stored)
    val viewModel = viewModel(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1"))
    runPending()
    assertEquals(setOf("IN", "SC"), viewModel.uiState.value.form.allowedSections)

    viewModel.onSectionToggle("SC")
    viewModel.onSectionToggle("MA")
    viewModel.onSubmit()
    runPending()

    assertEquals(listOf("IN", "MA"), repository.updateRequests.single().allowedSections)
  }

  @Test
  fun editing_canOpenTheEventToEverySectionAgain() {
    val stored =
        completeForm()
            .copy(isPrivate = false, allowedSections = setOf("IN"))
            .toEvent(ZURICH)!!
            .withCreator("organizer", isVerifiedAssociation = false)
            .copy(id = "event-1")
    repository.seed(stored)
    val viewModel = viewModel(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1"))
    runPending()

    viewModel.onSectionToggle("IN")
    viewModel.onSubmit()
    runPending()

    assertTrue(repository.updateRequests.single().allowedSections.isEmpty())
  }
}
