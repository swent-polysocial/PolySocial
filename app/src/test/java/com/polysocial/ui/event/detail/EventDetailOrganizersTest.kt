// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests of every organizer's name on the
// detail, #371).
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
import com.polysocial.ui.event.studentEvent
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A student's public event names all its organizers, the creator first, then the co-organizers in
 * the order they were added, to the same viewers as the creator's name (#371, decision 22): the
 * students going and the organizers. Every name and account here is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailOrganizersTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
  private val events = FakeEventRepository()
  private val network = FakeNetworkMonitor()
  private val registrations =
      FakeRegistrationRepository(currentUid = ME).apply { readableEventIds += EVENT }
  private val profiles =
      FakePublicProfileRepository(
          mutableMapOf(
              CREATOR to PublicProfile(CREATOR, CREATOR_NAME, "IN", "BA3"),
              FIRST to PublicProfile(FIRST, FIRST_NAME, "SC", "BA1"),
              SECOND to PublicProfile(SECOND, SECOND_NAME, "MA", "MA1"),
          )
      )
  private var me = AuthUser(ME, "fictional.student@epfl.ch", true)

  /** A student's public event, created by [CREATOR], with [FIRST] then [SECOND] added after. */
  private val event =
      studentEvent(validEvent())
          .copy(
              id = EVENT,
              createdBy = CREATOR,
              organizerIds = listOf(CREATOR, FIRST, SECOND),
              allowedUids = listOf(CREATOR, FIRST, SECOND),
          )

  private fun TestScope.open(
      opened: Event = event,
      profileRepository: PublicProfileRepository = profiles,
  ): EventDetailViewModel {
    events.seed(opened)
    val vm =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            network,
            FakeAuthRepository(me),
            clock,
            FakeAssociationNames(emptyMap()),
            registrations,
            FakeGroupRepository(),
            profileRepository,
            FakeReminderScheduler(clock),
        )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    runCurrent()
    return vm
  }

  private val EventDetailViewModel.loaded
    get() = uiState.value as EventDetailUiState.Loaded

  /** The UIDs whose public profile was read, one per read. */
  private val readUids
    get() = profiles.requestedUids.map { it.single() }

  @Test
  fun aStudentGoing_getsEveryOrganizer_theCreatorFirst() = runTest {
    registrations.seedRegistration(EVENT, ME)

    val vm = open()

    assertEquals(listOf(CREATOR_NAME, FIRST_NAME, SECOND_NAME), vm.loaded.organizerNames)
    assertEquals(listOf(CREATOR, FIRST, SECOND), readUids)
  }

  @Test
  fun theCreatorFirst_evenWhenTheStoredListDoesntStartWithThem() = runTest {
    registrations.seedRegistration(EVENT, ME)

    val vm = open(event.copy(organizerIds = listOf(FIRST, CREATOR, SECOND)))

    assertEquals(listOf(CREATOR_NAME, FIRST_NAME, SECOND_NAME), vm.loaded.organizerNames)
  }

  @Test
  fun aCoOrganizer_getsEveryOrganizer() = runTest {
    me = AuthUser(SECOND, "fictional.second@epfl.ch", true)

    val vm = open()

    assertTrue(vm.loaded.isOrganizer)
    assertEquals(listOf(CREATOR_NAME, FIRST_NAME, SECOND_NAME), vm.loaded.organizerNames)
  }

  @Test
  fun aStudentWhoIsntGoing_getsNoOrganizer_andNoProfileIsRead() = runTest {
    val vm = open()

    assertEquals(emptyList<String>(), vm.loaded.organizerNames)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun aPrivateEvent_namesNoOrganizer() = runTest {
    registrations.seedRegistration(EVENT, ME)

    val vm = open(event.copy(isPrivate = true, allowedUids = listOf(CREATOR, FIRST, SECOND, ME)))

    assertEquals(emptyList<String>(), vm.loaded.organizerNames)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun aCoOrganizerWithoutAProfile_leavesOnlyTheirNameOut() = runTest {
    registrations.seedRegistration(EVENT, ME)
    profiles.profiles.remove(FIRST)

    val vm = open()

    assertEquals(listOf(CREATOR_NAME, SECOND_NAME), vm.loaded.organizerNames)
    assertFalse(vm.loaded.isReadingOrganizerNames)
  }

  @Test
  fun aCoOrganizerAdded_whileTheDetailIsOpen_isNamedToo() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val vm = open(event.copy(organizerIds = listOf(CREATOR, FIRST)))
    assertEquals(listOf(CREATOR_NAME, FIRST_NAME), vm.loaded.organizerNames)

    events.remove(EVENT)
    events.seed(event)
    runCurrent()

    assertEquals(listOf(CREATOR_NAME, FIRST_NAME, SECOND_NAME), vm.loaded.organizerNames)
  }

  @Test
  fun aCoOrganizerRemoved_whileTheDetailIsOpen_isNoLongerNamed() = runTest {
    registrations.seedRegistration(EVENT, ME)
    val vm = open()

    events.remove(EVENT)
    events.seed(event.copy(organizerIds = listOf(CREATOR, SECOND)))
    runCurrent()

    assertEquals(listOf(CREATOR_NAME, SECOND_NAME), vm.loaded.organizerNames)
  }

  @Test
  fun aNameThatFailedForTheNetwork_isReadAgainOnceOnline() = runTest {
    registrations.seedRegistration(EVENT, ME)
    network.online = false
    events.readFromCache = true
    val offlineFirst = OfflineFor(FIRST)

    val vm = open(profileRepository = offlineFirst)
    assertEquals(listOf(CREATOR_NAME, SECOND_NAME), vm.loaded.organizerNames)

    offlineFirst.offline = null
    network.online = true
    events.readFromCache = false
    events.seed()
    runCurrent()

    assertEquals(listOf(CREATOR_NAME, FIRST_NAME, SECOND_NAME), vm.loaded.organizerNames)
  }

  /** [profiles], except that reading [offline]'s profile fails for the network. */
  private inner class OfflineFor(var offline: String?) : PublicProfileRepository by profiles {
    override suspend fun getPublicProfile(uid: String): PublicProfileResult =
        if (uid == offline) PublicProfileResult.NetworkError else profiles.getPublicProfile(uid)
  }

  private companion object {
    const val EVENT = "study"
    const val ME = "me"
    const val CREATOR = "creator"
    const val CREATOR_NAME = "Léa Martin"
    const val FIRST = "first"
    const val FIRST_NAME = "Tom Rossi"
    const val SECOND = "second"
    const val SECOND_NAME = "Arnav Shah"
  }
}
