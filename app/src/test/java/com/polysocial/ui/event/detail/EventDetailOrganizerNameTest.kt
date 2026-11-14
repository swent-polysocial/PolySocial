// Contributors: Claude Fable 5.2 (wrote these tests of who gets a student organizer's name, #281).
// Contributors: Claude Fable 5.2 (testing pass: viewers whose registration isn't known, reads that
// overlap or outlive the event's change, and no state naming the organizer to a student who isn't
// going, #281).
// Contributors: Claude (Opus 5.5, Anthropic) (returns to the detail after its screen stopped, and
// no read again once back online unless the last one failed for the network; whether a read runs,
// and from the first state of a student going, #281's review).
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
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.STUDENT_UID
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.studentEvent
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
  private var me: AuthUser? = AuthUser(ME, "fictional.student@epfl.ch", true)

  /** Every state the detail showed, in order. */
  private val shown = mutableListOf<EventDetailUiState>()

  /** A student's public event, created and organized by [CREATOR]. */
  private val event = studentEvent(validEvent()).copy(id = EVENT)

  private fun viewModel(
      profileRepository: PublicProfileRepository = profiles,
      registrationRepository: RegistrationRepository = registrations,
  ) =
      EventDetailViewModel(
          SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
          events,
          network,
          FakeAuthRepository(me),
          clock,
          associationNames,
          registrationRepository,
          FakeGroupRepository(),
          profileRepository,
          FakeReminderScheduler(clock),
      )

  /** Opens the detail of [opened] and collects its state like the screen does, into [shown]. */
  private fun TestScope.open(
      opened: Event = event,
      profileRepository: PublicProfileRepository = profiles,
      registrationRepository: RegistrationRepository = registrations,
  ): EventDetailViewModel {
    events.seed(opened)
    return viewModel(profileRepository, registrationRepository).also { vm ->
      show(vm)
      runCurrent()
    }
  }

  /** Collects [vm]'s state like the screen does, into [shown], until the returned job ends. */
  private fun TestScope.show(vm: EventDetailViewModel): Job =
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        vm.uiState.collect { shown += it }
      }

  /**
   * Opens the detail, shows the organizer's name, then puts another screen on top of it for longer
   * than the detail keeps listening ([away] runs meanwhile) and comes back to it, with the profile
   * reads made before forgotten.
   */
  private suspend fun TestScope.returnToTheDetail(
      away: suspend () -> Unit = {}
  ): EventDetailViewModel {
    registrations.seedRegistration(EVENT, ME)
    events.seed(event)
    val vm = viewModel()
    val screen = show(vm)
    runCurrent()
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)

    screen.cancel()
    advanceTimeBy(EventDetailViewModel.STOP_TIMEOUT_MILLIS + 1_000)
    runCurrent()
    away()
    runCurrent()
    profiles.requestedUids.clear()

    show(vm)
    runCurrent()
    return vm
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

  /** Profiles whose reads each wait for their own answer, in [reads], in the order asked. */
  private class QueuedProfiles : PublicProfileRepository {
    val reads = mutableListOf<CompletableDeferred<PublicProfileResult>>()

    override suspend fun getPublicProfile(uid: String): PublicProfileResult =
        CompletableDeferred<PublicProfileResult>().also { reads += it }.await()

    override suspend fun getPublicProfiles(uids: Collection<String>) =
        PublicProfilesResult.Found(emptyMap())
  }

  /** Registrations that are only read once [answer] completes, like a slow connection. */
  private inner class SlowRegistrations : RegistrationRepository by registrations {
    val answer = CompletableDeferred<Unit>()

    override fun observeMyRegistrations(): Flow<MyRegistrationsResult> = flow {
      answer.await()
      emitAll(registrations.observeMyRegistrations())
    }
  }

  /** The creator's profile as a read's answer, under [name]. */
  private fun named(name: String) =
      PublicProfileResult.Found(PublicProfile(CREATOR, name, "IN", "BA3"))

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
  fun whileTheNameIsRead_theDetailSaysSo_untilItArrives() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val slow = SlowProfiles()

    val vm = open(profileRepository = slow)
    assertTrue(vm.loaded.isReadingOrganizerName)
    assertNull(vm.loaded.organizerName)

    slow.answer.complete(Unit)
    runCurrent()

    assertFalse(vm.loaded.isReadingOrganizerName)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
  }

  @Test
  fun aReadThatFindsNoName_stopsSayingItIsRead() = runTest {
    registrations.seedRegistration(EVENT, ME)
    profiles.failure = FakePublicProfileRepository.Failure.Unexpected
    val slow = SlowProfiles()
    val vm = open(profileRepository = slow)
    assertTrue(vm.loaded.isReadingOrganizerName)

    slow.answer.complete(Unit)
    runCurrent()

    assertFalse(vm.loaded.isReadingOrganizerName)
    assertNull(vm.loaded.organizerName)
  }

  @Test
  fun cancellingWhileTheNameIsRead_stopsSayingItIsRead() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val vm = open(profileRepository = SlowProfiles())
    assertTrue(vm.loaded.isReadingOrganizerName)

    vm.onCancelRegistration()
    runCurrent()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertFalse(vm.loaded.isReadingOrganizerName)
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
  fun aNameReadFromTheCacheOffline_staysOnceBackOnline_withoutBeingReadAgain() = runTest {
    registrations.seedRegistration(EVENT, ME)
    network.online = false
    events.readFromCache = true
    val slow = SlowProfiles().apply { answer.complete(Unit) }
    val vm = open(profileRepository = slow)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)

    // Back online, the name already read stays, with no server read (billed) to get it again.
    slow.answer = CompletableDeferred()
    network.online = true
    events.readFromCache = false
    events.seed()
    runCurrent()

    assertFalse(vm.loaded.isOffline)
    assertEquals(1, slow.reads)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)

    // Nor when the connection is lost again.
    network.online = false
    events.readFromCache = true
    events.seed()
    runCurrent()

    assertTrue(vm.loaded.isOffline)
    assertEquals(1, slow.reads)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
  }

  @Test
  fun aProfileThatFailedForAnotherReason_isntReadAgainOnceBackOnline() = runTest {
    // A malformed profile, say: unlike the network, coming back online doesn't change it.
    registrations.seedRegistration(EVENT, ME)
    network.online = false
    events.readFromCache = true
    profiles.failure = FakePublicProfileRepository.Failure.Unexpected
    val vm = open()
    assertNull(vm.loaded.organizerName)

    network.online = true
    events.readFromCache = false
    events.seed()
    runCurrent()

    assertFalse(vm.loaded.isOffline)
    assertNull(vm.loaded.organizerName)
    assertEquals(listOf(CREATOR), readUids)
  }

  @Test
  fun aReturnAfterTheRegistrationWasCancelledElsewhere_readsNoProfile_andShowsNoName() = runTest {
    // Cancelled from My events, for example, while the detail was in the background.
    val vm = returnToTheDetail(away = { registrations.cancel(EVENT) })

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertNull(vm.loaded.organizerName)
    assertEquals(emptyList<String>(), readUids)
  }

  @Test
  fun aReturnWithNothingChanged_showsTheNameAlreadyRead_withoutReadingIt() = runTest {
    val vm = returnToTheDetail()

    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
    assertEquals(emptyList<String>(), readUids)
  }

  @Test
  fun aStudentGoing_seesTheNameOrItsHeldPlace_inEveryState() = runTest {
    // Opening the detail, then coming back to it: the row never closes before the name comes, so
    // nothing below it moves.
    returnToTheDetail()

    val going =
        shown.filterIsInstance<EventDetailUiState.Loaded>().filter {
          it.attendance == Attendance.GOING
        }
    assertTrue(going.isNotEmpty())
    assertEquals(
        emptyList<EventDetailUiState.Loaded>(),
        going.filter { it.organizerName == null && !it.isReadingOrganizerName },
    )
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

  @Test
  fun aViewerWhoIsntSignedIn_getsNoName_andNoProfileIsRead() = runTest {
    me = null
    registrations.currentUid = null

    val vm = open()

    assertFalse(vm.loaded.isOrganizer)
    assertNull(vm.loaded.organizerName)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun aViewerWhoseRegistrationsCantBeRead_getsNoName_andNoProfileIsRead() = runTest {
    // An association, whose read of its registrations the rules refuse, or a failed query.
    registrations.seedRegistration(EVENT, ME)
    registrations.readFailure = true

    val vm = open()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertNull(vm.loaded.organizerName)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun untilTheRegistrationsAnswer_noProfileIsRead() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val slow = SlowRegistrations()

    val vm = open(registrationRepository = slow)
    assertEquals(Attendance.UNKNOWN, vm.loaded.attendance)
    assertNull(vm.loaded.organizerName)
    assertTrue(profiles.requestedUids.isEmpty())

    slow.answer.complete(Unit)
    runCurrent()

    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
    assertEquals(listOf(CREATOR), readUids)
  }

  @Test
  fun registeringAgainWhileTheFirstReadRuns_keepsOnlyTheLatestReadsName() = runTest {
    val queued = QueuedProfiles()
    val vm = open(profileRepository = queued)

    vm.onImGoing()
    runCurrent()
    vm.onCancelRegistration()
    runCurrent()
    vm.onImGoing()
    runCurrent()
    assertEquals(2, queued.reads.size)
    assertNull(vm.loaded.organizerName)

    queued.reads[1].complete(named(NEW_NAME))
    runCurrent()
    assertEquals(NEW_NAME, vm.loaded.organizerName)

    // The first read's late answer, an older copy of the profile, changes nothing.
    queued.reads[0].complete(named(CREATOR_NAME))
    runCurrent()
    assertEquals(NEW_NAME, vm.loaded.organizerName)
  }

  @Test
  fun aReadReplacedWhenBackOnline_cantOverwriteTheNewOnesName() = runTest {
    registrations.seedRegistration(EVENT, ME)
    network.online = false
    events.readFromCache = true
    val queued = QueuedProfiles()
    val vm = open(profileRepository = queued)
    assertEquals(1, queued.reads.size)

    // Back online, the name is read again while the offline read still waits.
    network.online = true
    events.readFromCache = false
    events.seed()
    runCurrent()
    assertEquals(2, queued.reads.size)
    queued.reads[1].complete(named(NEW_NAME))
    runCurrent()
    assertEquals(NEW_NAME, vm.loaded.organizerName)

    // The offline read answers last, with the copy it had: it was replaced, so it changes nothing.
    queued.reads[0].complete(named(CREATOR_NAME))
    runCurrent()
    assertEquals(NEW_NAME, vm.loaded.organizerName)
  }

  @Test
  fun anEventMadePrivateWhileTheNameIsRead_neverShowsIt() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val slow = SlowProfiles()
    val vm = open(profileRepository = slow)

    events.remove(EVENT)
    events.seed(event.copy(isPrivate = true, allowedUids = listOf(CREATOR, ME)))
    runCurrent()
    assertTrue(vm.loaded.details.event.isPrivate)

    // The read's late answer is for an event that names nobody anymore.
    slow.answer.complete(Unit)
    runCurrent()

    assertNull(vm.loaded.organizerName)
    assertEquals(1, slow.reads)
  }

  @Test
  fun anEventDeletedWhileTheNameIsRead_showsNotFoundAtOnce() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val slow = SlowProfiles()
    val vm = open(profileRepository = slow)

    events.remove(EVENT)
    runCurrent()
    // The read that is still running doesn't hold the deleted event on screen.
    assertEquals(EventDetailUiState.NotFound, vm.uiState.value)

    slow.answer.complete(Unit)
    runCurrent()

    assertEquals(EventDetailUiState.NotFound, vm.uiState.value)
  }

  @Test
  fun noStateEverNamesTheOrganizerToAStudentWhoIsntGoing() = runTest {
    val vm = open()

    vm.onImGoing()
    runCurrent()
    vm.onCancelRegistration()
    runCurrent()
    // Then registered and cancelled elsewhere: the detail observes the registrations live.
    registrations.seedRegistration(EVENT, ME)
    runCurrent()
    assertEquals(CREATOR_NAME, vm.loaded.organizerName)
    registrations.cancel(EVENT)
    runCurrent()

    // Not even the state between the cancellation and the name's removal.
    val withAName =
        shown.filterIsInstance<EventDetailUiState.Loaded>().filter { it.organizerName != null }
    assertTrue(withAName.isNotEmpty())
    assertTrue(withAName.all { it.attendance == Attendance.GOING })
  }

  private companion object {
    const val EVENT = "study"
    const val ME = "me"
    const val MY_NAME = "Sam Student"
    const val CREATOR = STUDENT_UID
    const val CREATOR_NAME = "Olivia Organizer"
    /** The name the organizer changed to, which only a newer read answers. */
    const val NEW_NAME = "Olivia Renamed"
  }
}
