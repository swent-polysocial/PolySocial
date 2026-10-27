// Contributors: Claude (wrote these tests, #134; additional tests from a test audit, #134).
package com.polysocial.ui.event.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.UpdateEventResult
import com.polysocial.model.event.withCreator
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Who the Create Event form says organizes the event (#134): a verified association sees its name,
 * everyone else (and anyone whose association can't be read) sees "Organized by you".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateEventOrganizerTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val events = FakeEventRepository(currentUid = ORGANIZER_UID, now = FORM_NOW)
  private val auth =
      FakeAuthRepository(AuthUser(ORGANIZER_UID, ORGANIZER_EMAIL, isEmailVerified = true))
  private val profiles =
      FakeUserProfileRepository(ProfileResult.Found(verifiedAssociationProfile()))
  private val associations = FakeAssociationRepository(AssociationResult.Found(TEST_CLUB))

  private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
      CreateEventViewModel(
          events,
          Clock.fixed(FORM_NOW, ZURICH),
          savedState,
          auth,
          profiles,
          associations,
      )

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  /** Fills in every field of [form] through the ViewModel, as the organizer would. */
  private fun CreateEventViewModel.fill(form: CreateEventForm) {
    onTitleChange(form.title)
    onDescriptionChange(form.description)
    onCategorySelect(form.category!!)
    onDatePick(form.date!!)
    onStartTimePick(form.startTime!!)
    onEndTimePick(form.endTime)
    onLocationPick(form.location!!)
    onCapacityChange(form.capacityText)
  }

  /** The organizer once every read has answered. */
  private fun organizerOf(viewModel: CreateEventViewModel): Organizer {
    runPending()
    return viewModel.uiState.value.organizer
  }

  @Test
  fun aVerifiedAssociation_isShownByTheNameOfItsOwnAssociation() {
    assertEquals(Organizer.Association("Test Club"), organizerOf(viewModel()))
    assertEquals(ORGANIZER_UID, profiles.lastRequestedUid)
    assertEquals(listOf(ORGANIZER_UID), associations.requestedUids)
  }

  @Test
  fun theAssociationsName_staysWhileTheFormIsFilledInAndSubmitted() {
    val viewModel = viewModel()
    runPending()

    viewModel.fill(completeForm())
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)
    viewModel.onSubmit()
    runPending()

    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Created)
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)
  }

  @Test
  fun aStudent_isShownAsYou_withoutReadingAnyAssociation() {
    profiles.getProfileResult = ProfileResult.Found(studentProfile())

    assertEquals(Organizer.You, organizerOf(viewModel()))
    assertEquals(1, profiles.getProfileCalls)
    assertTrue(associations.requestedUids.isEmpty())
  }

  @Test
  fun aPendingAssociation_isShownAsYou_withoutReadingItsAssociation() {
    // It can't publish under its name yet: its events get no badge (#45).
    profiles.getProfileResult =
        ProfileResult.Found(verifiedAssociationProfile().copy(isAssociationVerified = false))

    assertEquals(Organizer.You, organizerOf(viewModel()))
    assertTrue(associations.requestedUids.isEmpty())
  }

  @Test
  fun aVerifiedFlagOnAStudentProfile_isShownAsYou() {
    profiles.getProfileResult =
        ProfileResult.Found(studentProfile().copy(isAssociationVerified = true))

    assertEquals(Organizer.You, organizerOf(viewModel()))
    assertTrue(associations.requestedUids.isEmpty())
  }

  @Test
  fun anAssociationThatCantBeRead_fallsBackToYou() {
    for (failure in
        listOf(
            AssociationResult.NotFound,
            AssociationResult.NetworkError,
            AssociationResult.UnexpectedError,
        )) {
      associations.getAssociationResult = failure

      assertEquals("After $failure", Organizer.You, organizerOf(viewModel()))
    }
  }

  @Test
  fun anAssociationWithABlankName_fallsBackToYou() {
    associations.getAssociationResult = AssociationResult.Found(TEST_CLUB.copy(name = " \t "))

    assertEquals(Organizer.You, organizerOf(viewModel()))
  }

  @Test
  fun aProfileThatCantBeRead_fallsBackToYou_withoutReadingAnyAssociation() {
    for (failure in
        listOf(ProfileResult.NotFound, ProfileResult.NetworkError, ProfileResult.UnexpectedError)) {
      profiles.getProfileResult = failure

      assertEquals("After $failure", Organizer.You, organizerOf(viewModel()))
    }
    assertTrue(associations.requestedUids.isEmpty())
  }

  @Test
  fun nobodySignedIn_isShownAsYou_withoutReadingAnything() {
    auth.user = null

    assertEquals(Organizer.You, organizerOf(viewModel()))
    assertEquals(0, profiles.getProfileCalls)
    assertTrue(associations.requestedUids.isEmpty())
  }

  @Test
  fun theOrganizer_isLoadingUntilTheProfileAnswers() {
    profiles.gate = CompletableDeferred()
    val viewModel = viewModel()

    assertEquals(Organizer.Loading, organizerOf(viewModel))

    profiles.gate!!.complete(Unit)
    assertEquals(Organizer.Association("Test Club"), organizerOf(viewModel))
  }

  @Test
  fun theOrganizer_isLoadingUntilTheAssociationAnswers() {
    associations.gate = CompletableDeferred()
    val viewModel = viewModel()

    assertEquals(Organizer.Loading, organizerOf(viewModel))

    associations.gate!!.complete(Unit)
    assertEquals(Organizer.Association("Test Club"), organizerOf(viewModel))
  }

  @Test
  fun theFormWorks_whileTheOrganizerIsLoading() {
    profiles.gate = CompletableDeferred()
    val viewModel = viewModel()
    viewModel.fill(completeForm())

    viewModel.onSubmit()
    runPending()

    assertEquals(Organizer.Loading, viewModel.uiState.value.organizer)
    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Created)
    assertEquals(listOf("Study together"), events.events.map { it.title })
  }

  @Test
  fun theFormWorks_whenTheAssociationCantBeRead() {
    associations.getAssociationResult = AssociationResult.NetworkError
    val viewModel = viewModel()
    runPending()
    viewModel.fill(completeForm())

    viewModel.onSubmit()
    runPending()

    assertEquals(Organizer.You, viewModel.uiState.value.organizer)
    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Created)
    assertEquals(listOf("Study together"), events.events.map { it.title })
  }

  @Test
  fun editingAnEvent_alsoShowsTheVerifiedAssociationsName() {
    events.seed(
        completeForm()
            .toEvent(ZURICH)!!
            .withCreator(ORGANIZER_UID, isVerifiedAssociation = true)
            .copy(id = "event-1")
    )

    val viewModel =
        viewModel(SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1")))

    assertEquals(Organizer.Association("Test Club"), organizerOf(viewModel))
    assertEquals(CreateEventStatus.Editing, viewModel.uiState.value.status)
    assertEquals("Study together", viewModel.uiState.value.form.title)
  }

  @Test
  fun editingAnEvent_asAStudent_showsYou() {
    profiles.getProfileResult = ProfileResult.Found(studentProfile())
    events.seed(
        completeForm()
            .toEvent(ZURICH)!!
            .withCreator(ORGANIZER_UID, isVerifiedAssociation = false)
            .copy(id = "event-1")
    )

    val viewModel =
        viewModel(SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1")))

    assertEquals(Organizer.You, organizerOf(viewModel))
    assertTrue(associations.requestedUids.isEmpty())
  }

  @Test
  fun editingAnEvent_whenTheEventIsReadBeforeTheOrganizer_keepsTheFormAndTheOrganizer() {
    profiles.gate = CompletableDeferred()
    events.seed(
        completeForm()
            .toEvent(ZURICH)!!
            .withCreator(ORGANIZER_UID, isVerifiedAssociation = true)
            .copy(id = "event-1")
    )
    val viewModel =
        viewModel(SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1")))

    assertEquals(Organizer.Loading, organizerOf(viewModel))
    assertEquals("Study together", viewModel.uiState.value.form.title)
    assertEquals(CreateEventStatus.Editing, viewModel.uiState.value.status)

    profiles.gate!!.complete(Unit)

    assertEquals(Organizer.Association("Test Club"), organizerOf(viewModel))
    assertEquals("Study together", viewModel.uiState.value.form.title)
    assertEquals(CreateEventStatus.Editing, viewModel.uiState.value.status)
    assertTrue(viewModel.uiState.value.isEditing)
  }

  @Test
  fun theOrganizer_readWhileTheEventIsBeingSaved_isKeptOnceItIsCreated() {
    profiles.gate = CompletableDeferred()
    events.pending = CompletableDeferred()
    val viewModel = viewModel()
    viewModel.fill(completeForm())
    viewModel.onSubmit()
    runPending()
    assertEquals(CreateEventStatus.Saving, viewModel.uiState.value.status)

    profiles.gate!!.complete(Unit)
    runPending()
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)
    assertEquals(CreateEventStatus.Saving, viewModel.uiState.value.status)
    assertEquals("Study together", viewModel.uiState.value.form.title)

    events.pending!!.complete(Unit)
    runPending()
    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Created)
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)
  }

  @Test
  fun aFailedCreation_andTryAgain_keepTheOrganizer() {
    val viewModel = viewModel()
    runPending()
    viewModel.fill(completeForm())
    events.failure = CreateEventResult.NetworkError

    viewModel.onSubmit()
    runPending()
    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Failed)
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)

    events.failure = null
    viewModel.onSubmit()
    runPending()
    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Created)
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)
  }

  @Test
  fun aFailedOrRefusedEdit_andTheSavedEdit_keepTheOrganizer() {
    events.seed(
        completeForm()
            .toEvent(ZURICH)!!
            .withCreator(ORGANIZER_UID, isVerifiedAssociation = true)
            .copy(id = "event-1")
    )
    val viewModel =
        viewModel(SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1")))
    runPending()

    events.updateFailure = UpdateEventResult.NotOrganizer
    viewModel.onSubmit()
    runPending()
    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Failed)
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)

    // A blank title is caught before sending: the form stays editable, with its error.
    viewModel.onTitleChange("")
    events.updateFailure = null
    viewModel.onSubmit()
    runPending()
    assertEquals(CreateEventStatus.Editing, viewModel.uiState.value.status)
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)

    viewModel.onTitleChange("Study together")
    viewModel.onSubmit()
    runPending()
    assertEquals(CreateEventStatus.Saved, viewModel.uiState.value.status)
    assertEquals(Organizer.Association("Test Club"), viewModel.uiState.value.organizer)
  }

  @Test
  fun closingTheScreenWhileTheProfileIsRead_stopsTheReadQuietly() {
    profiles.gate = CompletableDeferred()
    val store = ViewModelStore()
    val factory = viewModelFactory { initializer { viewModel() } }
    val viewModel = ViewModelProvider.create(store, factory)[CreateEventViewModel::class]
    runPending()
    assertEquals(1, profiles.getProfileCalls)

    store.clear()
    profiles.gate!!.complete(Unit)
    runPending()

    // The read was cancelled with the screen: the association is never asked for.
    assertTrue(associations.requestedUids.isEmpty())
    assertEquals(Organizer.Loading, viewModel.uiState.value.organizer)
  }
}
