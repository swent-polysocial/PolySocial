// Contributors: Claude Fable 5.2 (wrote these tests of who gets a student organizer's name, #281).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.STUDENT_UID
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.studentEvent
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A student's public event names its organizer only to the students registered for it and to its
 * organizers (#281). Every name and account here is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailOrganizerNameTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  /** The day before the event starts. */
  private val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
  private val events = FakeEventRepository()
  private val network = FakeNetworkMonitor()
  private val registrations =
      FakeRegistrationRepository(currentUid = ME).apply { readableEventIds += EVENT }
  private val profiles =
      FakePublicProfileRepository(
          mutableMapOf(
              CREATOR to PublicProfile(CREATOR, CREATOR_NAME, "IN", "BA3"),
              ME to PublicProfile(ME, MY_NAME, "SC", "MA1"),
          )
      )
  private val associationNames = FakeAssociationNames(mapOf(CREATOR to CLUB_NAME))
  private var me = AuthUser(ME, "fictional.student@epfl.ch", true)

  /** A student's public event, created and organized by [CREATOR]. */
  private val event = studentEvent(validEvent()).copy(id = EVENT)

  private fun viewModel(profileRepository: PublicProfileRepository = profiles) =
      EventDetailViewModel(
          SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
          events,
          network,
          FakeAuthRepository(me),
          clock,
          associationNames,
          registrations,
          FakeGroupRepository(),
          profileRepository,
          FakeReminderScheduler(clock),
      )

  /** Opens the detail of [shown] and collects its state like the screen does. */
  private fun TestScope.open(
      shown: Event = event,
      profileRepository: PublicProfileRepository = profiles,
  ): EventDetailViewModel {
    events.seed(shown)
    return viewModel(profileRepository).also { vm ->
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      runCurrent()
    }
  }

  private val EventDetailViewModel.loaded
    get() = uiState.value as EventDetailUiState.Loaded

  /** The UIDs whose public profile was read, one per read. */
  private val readUids
    get() = profiles.requestedUids.map { it.single() }

  /** Profiles whose reads wait for [answer], like a slow connection; [reads] counts them. */
  private inner class SlowProfiles : PublicProfileRepository by profiles {
    var answer = CompletableDeferred<Unit>()
    var reads = 0

    override suspend fun getPublicProfile(uid: String): PublicProfileResult {
      reads++
      answer.await()
      return profiles.getPublicProfile(uid)
    }
  }

  @Test
  fun aStudentRegisteredForTheEvent_getsItsOrganizersName() = runTest {
    registrations.seedRegistration(EVENT, ME)

    val vm = open()

    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
    assertEquals(listOf(CREATOR), readUids)
  }

  @Test
  fun aStudentWhoIsNotRegistered_getsNoName_andNoProfileIsRead() = runTest {
    val vm = open()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertNull(vm.loaded.organizerName)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun theOrganizer_getsTheNameTheStudentsGoingSee() = runTest {
    me = AuthUser(CREATOR, "fictional.organizer@epfl.ch", true)

    val vm = open()

    assertTrue(vm.loaded.isOrganizer)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
  }

  @Test
  fun anOrganizerWhoDidntCreateTheEvent_getsItsCreatorsName() = runTest {
    // What a co-organizer will see (#371): the rules already allow several organizers.
    val vm = open(event.copy(organizerIds = listOf(CREATOR, ME)))

    assertTrue(vm.loaded.isOrganizer)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
    assertEquals(listOf(CREATOR), readUids)
  }

  @Test
  fun anAssociationsEvent_keepsItsAssociationName_andNamesNoStudent() = runTest {
    registrations.seedRegistration(EVENT, ME)

    val vm = open(associationEvent(event, CREATOR))

    assertEquals(CLUB_NAME, vm.loaded.associationName)
    assertNull(vm.loaded.organizerName)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun aPrivateEvent_namesNobody() = runTest {
    registrations.seedRegistration(EVENT, ME)

    val vm = open(event.copy(isPrivate = true, allowedUids = listOf(CREATOR, ME)))

    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertNull(vm.loaded.organizerName)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun aMissingProfile_leavesTheEventWithoutAName() = runTest {
    // A deleted account, or a profile saved before public profiles existed (#291).
    registrations.seedRegistration(EVENT, ME)
    profiles.profiles.remove(CREATOR)

    val vm = open()

    assertNull(vm.loaded.organizerName)
    assertEquals(event.title, vm.loaded.details.event.title)
    assertEquals(listOf(CREATOR), readUids)
  }

  @Test
  fun aProfileThatCantBeRead_leavesTheEventWithoutAName() = runTest {
    registrations.seedRegistration(EVENT, ME)
    for (failure in FakePublicProfileRepository.Failure.entries) {
      profiles.failure = failure

      val vm = open()

      assertNull("After $failure", vm.loaded.organizerName)
      assertEquals(event.title, vm.loaded.details.event.title)
      assertEquals(Attendance.GOING, vm.loaded.attendance)
      events.events.clear()
    }
  }

  @Test
  fun aProfileWithoutAName_leavesTheEventWithoutAName() = runTest {
    registrations.seedRegistration(EVENT, ME)
    profiles.profiles[CREATOR] = PublicProfile(CREATOR, "  ", "IN", "BA3")

    assertNull(open().loaded.organizerName)
  }

  @Test
  fun anEventWithoutACreator_readsNoProfile() = runTest {
    // A stored event without `createdBy` reads as a blank UID, which has no profile to read.
    registrations.seedRegistration(EVENT, ME)

    val vm = open(event.copy(createdBy = ""))

    assertNull(vm.loaded.organizerName)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun theEventShows_beforeTheNameArrives() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val slow = SlowProfiles()

    val vm = open(profileRepository = slow)
    assertEquals(event.title, vm.loaded.details.event.title)
    assertNull(vm.loaded.organizerName)

    slow.answer.complete(Unit)
    runCurrent()

    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
  }

  @Test
  fun registering_showsTheName_andCancelling_removesIt_withoutReopening() = runTest {
    val vm = open()
    assertNull(vm.loaded.organizerName)

    vm.onImGoing()
    runCurrent()
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)

    vm.onCancelRegistration()
    runCurrent()
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertNull(vm.loaded.organizerName)

    vm.onImGoing()
    runCurrent()
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
  }

  @Test
  fun aRegistrationCancelledElsewhere_removesTheName() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val vm = open()
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)

    // From My events, for example: the detail observes the registrations live.
    registrations.cancel(EVENT)
    runCurrent()

    assertNull(vm.loaded.organizerName)
  }

  @Test
  fun cancellingWhileTheNameIsRead_neverShowsIt() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val slow = SlowProfiles()
    val vm = open(profileRepository = slow)

    vm.onCancelRegistration()
    runCurrent()
    slow.answer.complete(Unit)
    runCurrent()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertNull(vm.loaded.organizerName)
  }

  @Test
  fun aNameThatCouldntBeReadOffline_arrivesOnceBackOnline() = runTest {
    registrations.seedRegistration(EVENT, ME)
    network.online = false
    events.readFromCache = true
    profiles.failure = FakePublicProfileRepository.Failure.Offline
    val vm = open()
    assertTrue(vm.loaded.isOffline)
    assertNull(vm.loaded.organizerName)

    // Back online: the server confirms the same event.
    network.online = true
    events.readFromCache = false
    profiles.failure = null
    events.seed()
    runCurrent()

    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
  }

  @Test
  fun aNameReadFromTheCacheOffline_staysWhileItIsReadAgainOnline() = runTest {
    registrations.seedRegistration(EVENT, ME)
    network.online = false
    events.readFromCache = true
    val slow = SlowProfiles().apply { answer.complete(Unit) }
    val vm = open(profileRepository = slow)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)

    // Back online, the name is read again: the one already shown isn't taken away meanwhile.
    slow.answer = CompletableDeferred()
    network.online = true
    events.readFromCache = false
    events.seed()
    runCurrent()

    assertFalse(vm.loaded.isOffline)
    assertEquals(2, slow.reads)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)

    slow.answer.complete(Unit)
    runCurrent()
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
  }

  @Test
  fun everyOtherChangeOfTheEvent_keepsTheName_withoutReadingItAgain() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val vm = open()

    events.remove(EVENT)
    events.seed(event.copy(title = "Study session, moved"))
    runCurrent()

    assertEquals("Study session, moved", vm.loaded.details.event.title)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
    assertEquals(listOf(CREATOR), readUids)
  }

  private companion object {
    const val EVENT = "study"
    const val ME = "me"
    const val MY_NAME = "Sam Student"
    const val CREATOR = STUDENT_UID
    const val CREATOR_NAME = "Olivia Organizer"
  }
}
