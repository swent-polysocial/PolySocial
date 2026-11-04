// Contributors: Claude Opus 5.5 (medium) (wrote these tests, #204).
package com.polysocial.ui.event.list

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.ChosenEventsRepository
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MyEventsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val registrations = FakeRegistrationRepository(currentUid = STUDENT)
  private val fakeEvents = FakeEventRepository(currentUid = STUDENT)

  /** The fake events, except that an ID in [silentIds] never answers, like a late server. */
  private val silentIds = mutableSetOf<String>()
  private val events =
      object : EventRepository by fakeEvents {
        override fun getEvent(id: String): Flow<EventResult> =
            if (id in silentIds) flow { awaitCancellation() } else fakeEvents.getEvent(id)
      }

  /** A clock the tests move forward, starting at [TEST_NOW]. */
  private val clock =
      object : Clock() {
        var instant: Instant = TEST_NOW

        override fun instant() = instant

        override fun getZone(): ZoneId = ZoneId.of("Europe/Zurich")

        override fun withZone(zone: ZoneId) = throw UnsupportedOperationException()
      }
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private fun event(id: String, startsIn: Duration) =
      validEvent(title = "Event $id", startTime = TEST_NOW.plus(startsIn)).copy(id = id)

  private val tomorrow = event("tomorrow", Duration.ofDays(1))
  private val inTwoMonths = event("inTwoMonths", Duration.ofDays(60))
  private val lastWeek = event("lastWeek", Duration.ofDays(-7))

  private fun choose(vararg chosen: Event) {
    fakeEvents.seed(*chosen)
    chosen.forEach { registrations.seedRegistration(it.id, STUDENT) }
  }

  private fun newViewModel() =
      MyEventsViewModel(
          ChosenEventsRepository(registrations, events),
          registrations,
          network,
          clock,
      )

  private fun createViewModel() = newViewModel().also { runPending() }

  private val scheduler
    get() = mainDispatcherRule.dispatcher.scheduler

  private fun runPending() = scheduler.advanceUntilIdle()

  private val MyEventsViewModel.list
    get() = state.value.list

  private val MyEventsViewModel.events
    get() = list as MyEventsList.Events

  @Test
  fun beforeTheFirstResult_isLoading() {
    assertEquals(MyEventsList.Loading, newViewModel().list)
  }

  @Test
  fun chosenEvents_areSplitIntoUpcomingAndPast_withNoTimeWindow() {
    choose(inTwoMonths, lastWeek, tomorrow)

    val viewModel = createViewModel()

    assertEquals(
        MyEventsList.Events(
            upcoming = listOf(tomorrow, inTwoMonths),
            past = listOf(lastWeek),
            unavailableIds = emptyList(),
            missingCount = 0,
            now = TEST_NOW,
            isOffline = false,
        ),
        viewModel.list,
    )
    assertEquals(2, viewModel.state.value.upcomingCount)
  }

  @Test
  fun noRegistrations_isNothingPlanned_withACountOfZero() {
    val viewModel = createViewModel()

    assertEquals(MyEventsList.Empty, viewModel.list)
    assertEquals(0, viewModel.state.value.upcomingCount)
  }

  @Test
  fun registeringAndCancelling_updateTheList() {
    fakeEvents.seed(tomorrow)
    registrations.readableEventIds += tomorrow.id
    val viewModel = createViewModel()

    registrations.seedRegistration(tomorrow.id, STUDENT)
    runPending()
    assertEquals(listOf(tomorrow), viewModel.events.upcoming)

    runBlocking { registrations.cancel(tomorrow.id) }
    runPending()
    assertEquals(MyEventsList.Empty, viewModel.list)
  }

  @Test
  fun anEventThatEnds_movesToPast_onTheNextRefresh() {
    val endsSoon =
        event("endsSoon", Duration.ofMinutes(-30)).copy(endTime = TEST_NOW.plusSeconds(60))
    choose(endsSoon)
    val viewModel = createViewModel()
    assertEquals(listOf(endsSoon), viewModel.events.upcoming)

    clock.instant = TEST_NOW.plusSeconds(120)
    viewModel.refresh()

    assertEquals(listOf(endsSoon), viewModel.events.past)
    assertEquals(0, viewModel.state.value.upcomingCount)
  }

  @Test
  fun aDeletedEvent_isUnavailable_andRemoveDeletesTheRegistration() {
    choose(tomorrow, inTwoMonths)
    val viewModel = createViewModel()

    fakeEvents.remove(tomorrow.id)
    runPending()
    assertEquals(listOf(tomorrow.id), viewModel.events.unavailableIds)
    assertEquals(listOf(inTwoMonths), viewModel.events.upcoming)

    viewModel.remove(tomorrow.id)
    runPending()

    assertEquals(emptySet<String>(), registrations.attendeesOf(tomorrow.id))
    assertEquals(emptyList<String>(), viewModel.events.unavailableIds)
    assertNull(viewModel.state.value.message)
  }

  @Test
  fun removingOffline_keepsTheRow_andSaysWhy() {
    choose(tomorrow)
    fakeEvents.deniedIds += tomorrow.id
    registrations.cancelFailure = CancelRegistrationResult.NetworkError
    val viewModel = createViewModel()

    viewModel.remove(tomorrow.id)
    assertEquals(setOf(tomorrow.id), viewModel.state.value.removing)
    runPending()

    assertEquals(listOf(tomorrow.id), viewModel.events.unavailableIds)
    assertEquals(emptySet<String>(), viewModel.state.value.removing)
    assertEquals(MyEventsMessage.REMOVE_OFFLINE, viewModel.state.value.message)

    viewModel.messageShown()
    assertNull(viewModel.state.value.message)
  }

  @Test
  fun aFailedRemove_saysItFailed() {
    choose(tomorrow)
    fakeEvents.deniedIds += tomorrow.id
    registrations.cancelFailure = CancelRegistrationResult.UnexpectedError
    val viewModel = createViewModel()

    viewModel.remove(tomorrow.id)
    runPending()

    assertEquals(MyEventsMessage.REMOVE_FAILED, viewModel.state.value.message)
  }

  @Test
  fun offline_showsTheSavedEvents_andCountsTheOthers() {
    choose(tomorrow)
    registrations.seedRegistration("never-read", STUDENT)
    registrations.readFromCache = true
    fakeEvents.readFromCache = true
    online = false

    val viewModel = createViewModel()

    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertEquals(1, viewModel.events.missingCount)
    assertEquals(true, viewModel.events.isOffline)
  }

  @Test
  fun cachedResults_areNotOffline_whileTheDeviceIsOnline() {
    choose(tomorrow)
    fakeEvents.readFromCache = true

    assertEquals(false, createViewModel().events.isOffline)
  }

  @Test
  fun offline_withNothingSaved_isOfflineNothingSaved() {
    registrations.seedRegistration("never-read", STUDENT)
    registrations.readFromCache = true
    fakeEvents.readFromCache = true
    online = false

    assertEquals(MyEventsList.OfflineNothingSaved, createViewModel().list)
  }

  @Test
  fun offline_withNoCachedRegistrations_isOfflineNothingSaved_notNothingPlanned() {
    registrations.readFromCache = true
    online = false

    assertEquals(MyEventsList.OfflineNothingSaved, createViewModel().list)
  }

  @Test
  fun anEmptyCacheWhileOnline_waitsForTheServer() {
    registrations.readFromCache = true
    val viewModel = newViewModel()
    scheduler.runCurrent()

    assertEquals(MyEventsList.Loading, viewModel.list)
  }

  @Test
  fun anEventThatNeverAnswers_stopsLoadingAfterTheTimeout() {
    choose(tomorrow, inTwoMonths)
    silentIds += setOf(tomorrow.id, inTwoMonths.id)
    val viewModel = newViewModel()

    scheduler.advanceTimeBy(MyEventsViewModel.LOAD_TIMEOUT.inWholeMilliseconds - 1)
    assertEquals(MyEventsList.Loading, viewModel.list)

    scheduler.advanceTimeBy(2)
    assertEquals(MyEventsList.Error, viewModel.list)
  }

  @Test
  fun aSilentEvent_isCountedAsMissing_afterTheTimeout_nextToTheLoadedOnes() {
    choose(tomorrow, inTwoMonths)
    silentIds += inTwoMonths.id
    val viewModel = newViewModel()
    scheduler.runCurrent()
    assertEquals(0, viewModel.events.missingCount)

    runPending()

    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertEquals(1, viewModel.events.missingCount)
  }

  @Test
  fun failedRegistrations_areAnError_thatRetryRecoversFrom() {
    choose(tomorrow)
    registrations.readFailure = true
    val viewModel = createViewModel()
    assertEquals(MyEventsList.Error, viewModel.list)
    assertNull(viewModel.state.value.upcomingCount)

    registrations.readFailure = false
    viewModel.retry()
    assertEquals(MyEventsList.Loading, viewModel.list)
    runPending()

    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
  }

  @Test
  fun retry_doesNothingWhileTheListIsShown() {
    choose(tomorrow)
    val viewModel = createViewModel()

    viewModel.retry()

    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
  }

  private companion object {
    const val STUDENT = "test-student"
  }
}
