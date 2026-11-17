// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #370, and the undo of a
// self-removal, #418 review).
package com.polysocial.ui.event.create

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.OrganizerChanges
import com.polysocial.model.event.UpdateEventResult
import com.polysocial.model.event.withCreator
import com.polysocial.model.registration.AttendeesResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Edit event's organizers and the "Add a co-organizer" sheet in [CreateEventViewModel] (#370). */
class EditEventCoOrganizersTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val registrations = FakeRegistrationRepository()
  private val events =
      FakeEventRepository(
          currentUid = "creator",
          now = FORM_NOW,
          registeredUids = registrations::attendeesOf,
      )
  private val publicProfiles = FakePublicProfileRepository()

  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3")
  private val tom = PublicProfile("tom", "Tom Rossi", "SC", "BA3")
  private val arnav = PublicProfile("arnav", "Arnav Shah", "CS", "MA1")

  /** A student's event, created by "creator" and co-organized by Léa. */
  private val stored =
      completeForm()
          .toEvent(ZURICH)!!
          .withCreator("creator", isVerifiedAssociation = false)
          .copy(
              id = "event-1",
              organizerIds = listOf("creator", "lea"),
              allowedUids = listOf("creator", "lea"),
          )

  init {
    publicProfiles.profiles += listOf(lea, tom, arnav).associateBy { it.uid }
    for (uid in listOf("lea", "tom", "arnav")) registrations.seedRegistration("event-1", uid)
  }

  private fun editing(me: String = "creator", id: String? = "event-1"): CreateEventViewModel {
    events.currentUid = me
    return CreateEventViewModel(
        events,
        Clock.fixed(FORM_NOW, ZURICH),
        SavedStateHandle(id?.let { mapOf(EventDetailViewModel.EVENT_ID_ARG to it) }.orEmpty()),
        FakeAuthRepository(AuthUser(me, "$me@epfl.ch", true)),
        FakeUserProfileRepository(),
        FakeAssociationRepository(),
        registrations,
        publicProfiles,
    )
  }

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  private fun CreateEventViewModel.block() = uiState.value.coOrganizers!!

  private fun CreateEventViewModel.organizerUids() = block().organizers.map { it.uid }

  private fun opened(me: String = "creator"): CreateEventViewModel {
    events.seed(stored)
    return editing(me).also { runPending() }
  }

  @Test
  fun theBlock_listsTheOrganizersWithTheirProfiles_onceRead() {
    events.seed(stored)
    val viewModel = editing()
    runPending()

    assertEquals(
        listOf(
            OrganizerEntry("creator", null, isCreator = true, isYou = true),
            OrganizerEntry("lea", lea, isCreator = false, isYou = false),
        ),
        viewModel.block().organizers,
    )
    assertFalse(viewModel.block().profilesLoading)
    assertEquals(listOf(listOf("creator", "lea")), publicProfiles.requestedUids)
  }

  @Test
  fun unreadableProfiles_leaveTheRowsWithoutOne() {
    publicProfiles.failure = FakePublicProfileRepository.Failure.Offline

    val viewModel = opened()

    assertEquals(listOf(null, null), viewModel.block().organizers.map { it.profile })
    assertFalse(viewModel.block().profilesLoading)
  }

  @Test
  fun creatingAnEvent_orEditingAnAssociations_hasNoBlock() {
    assertNull(editing(id = null).also { runPending() }.uiState.value.coOrganizers)

    events.seed(stored.copy(isAssociationEvent = true))
    assertNull(editing().also { runPending() }.uiState.value.coOrganizers)
    assertTrue(publicProfiles.requestedUids.isEmpty())
  }

  @Test
  fun aCoOrganizer_seesTheCreatorByName_andThemselvesAsYou() {
    publicProfiles.profiles["creator"] = PublicProfile("creator", "Franz Keller", "IN", "BA5")

    val viewModel = opened(me = "lea")

    val (creator, you) = viewModel.block().organizers
    assertEquals("Franz Keller", creator.profile?.displayName)
    assertFalse(creator.isYou || creator.canRemove)
    assertTrue(you.isYou && you.canRemove)
  }

  @Test
  fun removing_changesOnlyTheForm_andTheCreatorStays() {
    val viewModel = opened()

    viewModel.onRemoveOrganizer("creator")
    viewModel.onRemoveOrganizer("lea")

    assertEquals(listOf("creator"), viewModel.organizerUids())
    assertEquals(stored.organizerIds, events.events.single().organizerIds)
  }

  @Test
  fun theSheet_offersTheStudentsGoingWhoArentOrganizers_byName() {
    val viewModel = opened()

    viewModel.onOpenAddCoOrganizer()
    assertEquals(AddCoOrganizerSheet.Loading, viewModel.block().sheet)
    runPending()

    assertEquals(AddCoOrganizerSheet.Students(listOf(arnav, tom)), viewModel.block().sheet)
  }

  @Test
  fun addingAStudent_putsThemInTheBlockAndClosesTheSheet() {
    val viewModel = opened()
    viewModel.onOpenAddCoOrganizer()
    runPending()

    viewModel.onAddCoOrganizer("tom")

    assertEquals(listOf("creator", "lea", "tom"), viewModel.organizerUids())
    assertEquals(tom, viewModel.block().organizers.last().profile)
    assertNull(viewModel.block().sheet)
  }

  @Test
  fun aStudentTheSheetDoesntOffer_isNotAdded() {
    val viewModel = opened()

    viewModel.onAddCoOrganizer("tom")
    viewModel.onOpenAddCoOrganizer()
    runPending()
    viewModel.onAddCoOrganizer("lea")

    assertEquals(listOf("creator", "lea"), viewModel.organizerUids())
  }

  @Test
  fun nobodyElseGoing_saysSo() {
    val viewModel = opened()
    registrations.attendeesFailure = AttendeesResult.Attendees(listOf("lea", "creator"))

    viewModel.onOpenAddCoOrganizer()
    runPending()

    assertEquals(AddCoOrganizerSheet.NobodyGoing, viewModel.block().sheet)
  }

  @Test
  fun studentsWithoutAProfile_areNotOffered() {
    val viewModel = opened()
    registrations.attendeesFailure = AttendeesResult.Attendees(listOf("ghost"))

    viewModel.onOpenAddCoOrganizer()
    runPending()

    assertEquals(AddCoOrganizerSheet.NobodyGoing, viewModel.block().sheet)
  }

  @Test
  fun aFailedRead_offersRetry() {
    val viewModel = opened()
    registrations.attendeesFailure = AttendeesResult.NetworkError

    viewModel.onOpenAddCoOrganizer()
    runPending()
    assertEquals(AddCoOrganizerSheet.Failed, viewModel.block().sheet)

    registrations.attendeesFailure = null
    viewModel.onRetryAddCoOrganizer()
    runPending()
    assertEquals(AddCoOrganizerSheet.Students(listOf(arnav, tom)), viewModel.block().sheet)
  }

  @Test
  fun unreadableStudentProfiles_failTheSheet() {
    val viewModel = opened()
    publicProfiles.failure = FakePublicProfileRepository.Failure.Unexpected

    viewModel.onOpenAddCoOrganizer()
    runPending()

    assertEquals(AddCoOrganizerSheet.Failed, viewModel.block().sheet)
  }

  @Test
  fun closingTheSheetWhileItLoads_keepsItClosed() {
    val viewModel = opened()

    viewModel.onOpenAddCoOrganizer()
    viewModel.onCloseAddCoOrganizer()
    runPending()

    assertNull(viewModel.block().sheet)
  }

  @Test
  fun atFiveOrganizers_theSheetDoesntOpen() {
    events.seed(stored.copy(organizerIds = listOf("creator", "lea", "a", "b", "c")))
    val viewModel = editing()
    runPending()

    viewModel.onOpenAddCoOrganizer()

    assertFalse(viewModel.block().canAdd)
    assertNull(viewModel.block().sheet)
  }

  @Test
  fun saving_sendsTheChangesWithTheEvent() {
    val viewModel = opened()
    viewModel.onRemoveOrganizer("lea")
    viewModel.onOpenAddCoOrganizer()
    runPending()
    viewModel.onAddCoOrganizer("arnav")

    viewModel.onSubmit()
    runPending()

    assertEquals(
        OrganizerChanges(added = setOf("arnav"), removed = setOf("lea")),
        events.organizerRequests.single(),
    )
    assertEquals(listOf("creator", "arnav"), events.events.single().organizerIds)
    assertEquals(CreateEventStatus.Saved, viewModel.uiState.value.status)
  }

  @Test
  fun savingOffline_keepsTheChangesWithTryAgain() {
    val viewModel = opened()
    viewModel.onRemoveOrganizer("lea")
    events.updateFailure = UpdateEventResult.NetworkError

    viewModel.onSubmit()
    runPending()
    assertEquals(
        CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.OFFLINE),
        viewModel.uiState.value.status,
    )
    assertEquals(listOf("creator"), viewModel.organizerUids())

    events.updateFailure = null
    viewModel.onSubmit()
    runPending()
    assertEquals(OrganizerChanges(removed = setOf("lea")), events.organizerRequests.last())
    assertEquals(listOf("creator"), events.events.single().organizerIds)
  }

  @Test
  fun aCoOrganizerRemovingThemselves_savesAndCloses() {
    val viewModel = opened(me = "lea")

    viewModel.onRemoveOrganizer("lea")
    viewModel.onSubmit()
    runPending()

    assertEquals(listOf("creator"), events.events.single().organizerIds)
    assertEquals(CreateEventStatus.Saved, viewModel.uiState.value.status)
  }

  @Test
  fun aCoOrganizerWhoRemovedThemselves_canAddThemselvesBack_keepingTheirOtherEdits() {
    val viewModel = opened(me = "lea")
    viewModel.onTitleChange("Exam review")
    viewModel.onRemoveOrganizer("lea")

    viewModel.onOpenAddCoOrganizer()
    runPending()
    assertEquals(AddCoOrganizerSheet.Students(listOf(arnav, lea, tom)), viewModel.block().sheet)
    viewModel.onAddCoOrganizer("lea")
    viewModel.onSubmit()
    runPending()

    assertEquals(OrganizerChanges.NONE, events.organizerRequests.single())
    assertEquals(stored.organizerIds, events.events.single().organizerIds)
    assertEquals("Exam review", events.events.single().title)
  }

  @Test
  fun aStudentWhoCancelledSinceBeingPicked_failsTheSave_untilRemoved() {
    // The sheet lists Dan, whose registration is gone by the time the organizer saves.
    publicProfiles.profiles["dan"] = PublicProfile("dan", "Dan Weber", "ME", "BA1")
    registrations.attendeesFailure = AttendeesResult.Attendees(listOf("dan"))
    val viewModel = opened()
    viewModel.onOpenAddCoOrganizer()
    runPending()
    viewModel.onAddCoOrganizer("dan")

    viewModel.onSubmit()
    runPending()
    assertEquals(
        CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.UNEXPECTED),
        viewModel.uiState.value.status,
    )
    assertEquals(stored.organizerIds, events.events.single().organizerIds)

    viewModel.onRemoveOrganizer("dan")
    assertEquals(CreateEventStatus.Editing, viewModel.uiState.value.status)
    viewModel.onSubmit()
    runPending()
    assertEquals(CreateEventStatus.Saved, viewModel.uiState.value.status)
  }

  @Test
  fun whileSaving_theOrganizersCantChange() {
    val viewModel = opened()
    viewModel.onSubmit()

    viewModel.onRemoveOrganizer("lea")
    viewModel.onOpenAddCoOrganizer()

    assertEquals(listOf("creator", "lea"), viewModel.organizerUids())
    assertNull(viewModel.block().sheet)
  }
}
