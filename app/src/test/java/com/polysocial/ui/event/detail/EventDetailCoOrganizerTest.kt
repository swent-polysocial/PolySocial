// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests of a co-organizer's actions, #371).
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
 * A co-organizer (#370) gets the organizer's actions on the detail, Edit and Cancel event, and
 * loses them once removed (#371): the detail reads them from the event's `organizerIds`, live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailCoOrganizerTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
  private val events = FakeEventRepository(currentUid = CO_ORGANIZER)

  /** A student's public event, created by [CREATOR] and co-organized by [CO_ORGANIZER]. */
  private val event =
      studentEvent(validEvent())
          .copy(
              id = EVENT,
              createdBy = CREATOR,
              organizerIds = listOf(CREATOR, CO_ORGANIZER),
              allowedUids = listOf(CREATOR, CO_ORGANIZER),
          )

  private fun TestScope.open(opened: Event = event): EventDetailViewModel {
    events.seed(opened)
    val vm =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            FakeNetworkMonitor(),
            FakeAuthRepository(AuthUser(CO_ORGANIZER, "fictional.co@epfl.ch", true)),
            clock,
            FakeAssociationNames(emptyMap()),
            FakeRegistrationRepository(currentUid = CO_ORGANIZER),
            FakeGroupRepository(currentUid = CO_ORGANIZER),
            FakePublicProfileRepository(),
            FakeReminderScheduler(clock),
        )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    runCurrent()
    return vm
  }

  private val EventDetailViewModel.loaded
    get() = uiState.value as EventDetailUiState.Loaded

  @Test
  fun aCoOrganizer_getsTheOrganizerActions() = runTest { assertTrue(open().loaded.isOrganizer) }

  @Test
  fun aCoOrganizerRemovedWhileTheDetailIsOpen_losesThem() = runTest {
    val vm = open()

    events.remove(EVENT)
    events.seed(event.copy(organizerIds = listOf(CREATOR)))
    runCurrent()

    assertFalse(vm.loaded.isOrganizer)
  }

  @Test
  fun aCoOrganizer_canCancelTheEvent() = runTest {
    val vm = open()

    vm.onCancelEvent()
    vm.onConfirmCancel()
    runCurrent()

    assertEquals(CancelEventState.Cancelled, vm.cancelState.value)
    assertEquals(listOf(EVENT), events.deleteRequests)
    assertTrue(events.events.isEmpty())
  }

  private companion object {
    const val EVENT = "study"
    const val CREATOR = "creator"
    const val CO_ORGANIZER = "co-organizer"
  }
}
