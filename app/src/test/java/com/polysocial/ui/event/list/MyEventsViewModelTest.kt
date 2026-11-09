// Contributors: Claude Opus 5.5 (medium) (wrote these tests, #204).
// Contributors: Claude (Opus 5.5, Anthropic) tested private events in My events (split, count,
// partial failures, Retry, loading and timeout), #320.
package com.polysocial.ui.event.list

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.PrivateEventsResult
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.ChosenEventsRepository
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MyEventsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val registrations = FakeRegistrationRepository(currentUid = STUDENT)
  private val fakeEvents = FakeEventRepository(currentUid = STUDENT)

  /**
   * The fake events, except that an ID in [silentIds] never answers, like a late server, an ID in
   * [lateEvents] answers once its value is set, and the private events come from [privateEvents]
   * when it is set.
   */
  private val silentIds = mutableSetOf<String>()
  private val lateEvents = mutableMapOf<String, MutableStateFlow<EventResult?>>()
  private var privateEvents: Flow<PrivateEventsResult>? = null
  private val events =
      object : EventRepository by fakeEvents {
        override fun getEvent(id: String): Flow<EventResult> =
            lateEvents[id]?.filterNotNull()
                ?: if (id in silentIds) flow { awaitCancellation() } else fakeEvents.getEvent(id)

        override fun getReadablePrivateEvents(): Flow<PrivateEventsResult> =
            privateEvents ?: fakeEvents.getReadablePrivateEvents()
      }

  /** The fake registrations, except that they come from [lateRegistrations] when it is set. */
  private var lateRegistrations: Flow<MyRegistrationsResult>? = null
  private val readRegistrations =
      object : RegistrationRepository by registrations {
        override fun observeMyRegistrations(): Flow<MyRegistrationsResult> =
            lateRegistrations ?: registrations.observeMyRegistrations()
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

  /** A private event the student may read (they organize it, are a member or were approved). */
  private fun readable(id: String, startsIn: Duration) =
      event(id, startsIn).copy(isPrivate = true, allowedUids = listOf("organizer", STUDENT))

  private fun choose(vararg chosen: Event) {
    fakeEvents.seed(*chosen)
    chosen.forEach { registrations.seedRegistration(it.id, STUDENT) }
  }

  private fun newViewModel() =
      MyEventsViewModel(
          ChosenEventsRepository(readRegistrations, events),
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
  fun removingAfterTheSessionEnded_saysItFailed() {
    choose(tomorrow)
    fakeEvents.deniedIds += tomorrow.id
    val viewModel = createViewModel()
    registrations.currentUid = null

    viewModel.remove(tomorrow.id)
    runPending()

    assertEquals(MyEventsMessage.REMOVE_FAILED, viewModel.state.value.message)
  }

  @Test
  fun aSecondRemove_whileTheFirstRuns_doesNothing() {
    choose(tomorrow)
    fakeEvents.deniedIds += tomorrow.id
    val answer = CompletableDeferred<CancelRegistrationResult>()
    var cancels = 0
    val slowCancel =
        object : RegistrationRepository by registrations {
          override suspend fun cancel(eventId: String): CancelRegistrationResult {
            cancels++
            return answer.await()
          }
        }
    val viewModel =
        MyEventsViewModel(ChosenEventsRepository(registrations, events), slowCancel, network, clock)
            .also { runPending() }

    viewModel.remove(tomorrow.id)
    viewModel.remove(tomorrow.id)
    scheduler.runCurrent()
    assertEquals(1, cancels)
    assertEquals(setOf(tomorrow.id), viewModel.state.value.removing)

    answer.complete(CancelRegistrationResult.Cancelled)
    runPending()
    assertEquals(emptySet<String>(), viewModel.state.value.removing)
    assertNull(viewModel.state.value.message)
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

  @Test
  fun keepSplitCurrent_movesAnEndedEventToPast_aMinuteLater() {
    val endsSoon =
        event("endsSoon", Duration.ofMinutes(-30)).copy(endTime = TEST_NOW.plusSeconds(30))
    choose(endsSoon)
    val viewModel = createViewModel()
    val splitting =
        CoroutineScope(mainDispatcherRule.dispatcher).launch { viewModel.keepSplitCurrent() }
    scheduler.runCurrent()
    assertEquals(listOf(endsSoon), viewModel.events.upcoming)

    clock.instant = TEST_NOW.plusSeconds(60)
    scheduler.advanceTimeBy(MyEventsViewModel.REFRESH_INTERVAL.inWholeMilliseconds)
    scheduler.runCurrent()

    assertEquals(listOf(endsSoon), viewModel.events.past)
    splitting.cancel()
  }

  @Test
  fun registrationsThatNeverAnswer_areAnError_afterTheTimeout() {
    val viewModel = viewModelWithSilentRegistrations()

    scheduler.advanceTimeBy(MyEventsViewModel.LOAD_TIMEOUT.inWholeMilliseconds - 1)
    assertEquals(MyEventsList.Loading, viewModel.list)

    scheduler.advanceTimeBy(2)
    assertEquals(MyEventsList.Error, viewModel.list)
  }

  @Test
  fun registrationsThatNeverAnswer_whileOffline_areOfflineNothingSaved_afterTheTimeout() {
    online = false
    val viewModel = viewModelWithSilentRegistrations()

    scheduler.advanceTimeBy(MyEventsViewModel.LOAD_TIMEOUT.inWholeMilliseconds + 1)

    assertEquals(MyEventsList.OfflineNothingSaved, viewModel.list)
  }

  @Test
  fun privateEventsTheStudentMayRead_areSplitAndCounted_withTheRegisteredOnes() {
    val organized = readable("organized", Duration.ofDays(3))
    val organizedLastMonth = readable("organizedLastMonth", Duration.ofDays(-30))
    choose(tomorrow, lastWeek)
    fakeEvents.seed(organized, organizedLastMonth)

    val viewModel = createViewModel()

    assertEquals(listOf(tomorrow, organized), viewModel.events.upcoming)
    assertEquals(listOf(lastWeek, organizedLastMonth), viewModel.events.past)
    assertEquals(2, viewModel.state.value.upcomingCount)
    assertEquals(false, viewModel.events.incomplete)
  }

  @Test
  fun aRegisteredPrivateEvent_isShownAndCountedOnce() {
    choose(readable("registeredPrivate", Duration.ofDays(1)))

    val viewModel = createViewModel()

    assertEquals(listOf("registeredPrivate"), viewModel.events.upcoming.map { it.id })
    assertEquals(1, viewModel.state.value.upcomingCount)
  }

  @Test
  fun aNewPrivateEvent_shows_andOneThatIsDeleted_leavesWithNothingToRemove() {
    val viewModel = createViewModel()
    assertEquals(MyEventsList.Empty, viewModel.list)

    val created = readable("created", Duration.ofDays(1))
    fakeEvents.seed(created)
    runPending()
    assertEquals(listOf(created), viewModel.events.upcoming)

    fakeEvents.remove(created.id)
    runPending()
    assertEquals(MyEventsList.Empty, viewModel.list)
  }

  @Test
  fun failedPrivateEvents_showTheRegisteredOnes_asIncomplete_andRetryReadsBothAgain() {
    choose(tomorrow)
    fakeEvents.privateEventsFailure = true
    val viewModel = createViewModel()

    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertEquals(true, viewModel.events.incomplete)
    // Figma omits an incomplete count.
    assertNull(viewModel.state.value.upcomingCount)

    fakeEvents.privateEventsFailure = false
    val organized = readable("organized", Duration.ofDays(3))
    fakeEvents.seed(organized)
    viewModel.retry()
    // The events stay on screen while they are read again.
    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    runPending()

    assertEquals(listOf(tomorrow, organized), viewModel.events.upcoming)
    assertEquals(false, viewModel.events.incomplete)
    assertEquals(2, viewModel.state.value.upcomingCount)
  }

  @Test
  fun retry_keepsTheEventsShown_whileTheRegistrationsAnswerBeforeTheirEvents() {
    choose(tomorrow)
    fakeEvents.privateEventsFailure = true
    val viewModel = createViewModel()
    val shown = viewModel.events
    assertEquals(true, shown.incomplete)

    // Like Firestore: the registrations answer first, then the private events, then the event.
    val eventAnswer = MutableStateFlow<EventResult?>(null)
    lateEvents[tomorrow.id] = eventAnswer
    val privateAnswer = MutableStateFlow<PrivateEventsResult?>(null)
    privateEvents = privateAnswer.filterNotNull()
    viewModel.retry()
    scheduler.runCurrent()
    assertEquals(shown, viewModel.list)

    privateAnswer.value = PrivateEventsResult.Events(emptyList(), fromCache = false)
    scheduler.runCurrent()
    assertEquals(shown, viewModel.list)

    eventAnswer.value = EventResult.Found(tomorrow, fromCache = false)
    scheduler.runCurrent()
    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertEquals(false, viewModel.events.incomplete)
    assertEquals(1, viewModel.state.value.upcomingCount)
  }

  @Test
  fun retry_keepsTheEventsShown_whileThePrivateEventsAnswerFirst_withNothing() {
    choose(tomorrow)
    fakeEvents.privateEventsFailure = true
    val viewModel = createViewModel()
    val shown = viewModel.events

    fakeEvents.privateEventsFailure = false
    val registrationsAnswer = MutableStateFlow<MyRegistrationsResult?>(null)
    lateRegistrations = registrationsAnswer.filterNotNull()
    viewModel.retry()
    scheduler.runCurrent()
    assertEquals(shown, viewModel.list)

    registrationsAnswer.value =
        MyRegistrationsResult.Registrations(setOf(tomorrow.id), fromCache = false)
    scheduler.runCurrent()
    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertEquals(false, viewModel.events.incomplete)
  }

  @Test
  fun retry_whoseReadsNeverAnswer_keepsTheEventsShown_withTheirRetry_afterTheTimeout() {
    choose(tomorrow)
    fakeEvents.privateEventsFailure = true
    val viewModel = createViewModel()
    val shown = viewModel.events

    lateRegistrations = flow { awaitCancellation() }
    privateEvents = flow { awaitCancellation() }
    viewModel.retry()
    scheduler.advanceTimeBy(MyEventsViewModel.LOAD_TIMEOUT.inWholeMilliseconds + 1)

    assertEquals(shown, viewModel.list)
    assertEquals(true, viewModel.events.incomplete)
  }

  @Test
  fun failedRegistrations_showThePrivateEvents_asIncomplete() {
    val organized = readable("organized", Duration.ofDays(3))
    fakeEvents.seed(organized)
    registrations.readFailure = true

    val viewModel = createViewModel()

    assertEquals(listOf(organized), viewModel.events.upcoming)
    assertEquals(true, viewModel.events.incomplete)
  }

  @Test
  fun bothReadsFailing_isAnError() {
    registrations.readFailure = true
    fakeEvents.privateEventsFailure = true

    assertEquals(MyEventsList.Error, createViewModel().list)
  }

  @Test
  fun failedPrivateEvents_withNothingRegistered_isAnError_notNothingPlanned() {
    fakeEvents.privateEventsFailure = true

    assertEquals(MyEventsList.Error, createViewModel().list)
  }

  @Test
  fun failedPrivateEvents_keepLoadingWhileARegisteredEventLoads_thenShowItAsIncomplete() {
    choose(tomorrow)
    fakeEvents.privateEventsFailure = true
    val answer = MutableStateFlow<EventResult?>(null)
    lateEvents[tomorrow.id] = answer
    val viewModel = newViewModel()
    scheduler.runCurrent()
    assertEquals(MyEventsList.Loading, viewModel.list)

    answer.value = EventResult.Found(tomorrow, fromCache = false)
    scheduler.runCurrent()

    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertEquals(true, viewModel.events.incomplete)
  }

  @Test
  fun failedPrivateEvents_keepLoadingWhileTheRegistrationsAreSilent_untilTheTimeout() {
    fakeEvents.privateEventsFailure = true
    val viewModel = viewModelWithSilentRegistrations()

    scheduler.advanceTimeBy(MyEventsViewModel.LOAD_TIMEOUT.inWholeMilliseconds - 1)
    assertEquals(MyEventsList.Loading, viewModel.list)

    scheduler.advanceTimeBy(2)
    assertEquals(MyEventsList.Error, viewModel.list)
  }

  @Test
  fun theCount_isOmittedUntilBothReadsHaveAnswered() {
    choose(tomorrow)
    val answer = MutableStateFlow<PrivateEventsResult?>(null)
    privateEvents = answer.filterNotNull()
    val viewModel = newViewModel()
    scheduler.runCurrent()
    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertNull(viewModel.state.value.upcomingCount)

    answer.value = PrivateEventsResult.Events(emptyList(), fromCache = false)
    scheduler.runCurrent()

    assertEquals(1, viewModel.state.value.upcomingCount)
  }

  @Test
  fun nothingRegistered_whileThePrivateEventsLoad_isLoading_untilTheTimeout() {
    privateEvents = flow { awaitCancellation() }
    val viewModel = newViewModel()

    scheduler.advanceTimeBy(MyEventsViewModel.LOAD_TIMEOUT.inWholeMilliseconds - 1)
    assertEquals(MyEventsList.Loading, viewModel.list)

    scheduler.advanceTimeBy(2)
    assertEquals(MyEventsList.Error, viewModel.list)
  }

  @Test
  fun silentPrivateEvents_areIncompleteAfterTheTimeout_andALateAnswerStillShows() {
    choose(tomorrow)
    val answer = MutableStateFlow<PrivateEventsResult?>(null)
    privateEvents = answer.filterNotNull()
    val viewModel = newViewModel()
    scheduler.runCurrent()
    assertEquals(listOf(tomorrow), viewModel.events.upcoming)
    assertEquals(false, viewModel.events.incomplete)

    runPending()
    assertEquals(true, viewModel.events.incomplete)

    val organized = readable("organized", Duration.ofDays(3))
    answer.value = PrivateEventsResult.Events(listOf(organized), fromCache = false)
    scheduler.runCurrent()

    assertEquals(listOf(tomorrow, organized), viewModel.events.upcoming)
    assertEquals(false, viewModel.events.incomplete)
  }

  @Test
  fun offline_cachedPrivateEventsShow_withTheOfflineBanner() {
    val organized = readable("organized", Duration.ofDays(3))
    fakeEvents.seed(organized)
    fakeEvents.readFromCache = true
    registrations.readFromCache = true
    online = false

    val viewModel = createViewModel()

    assertEquals(listOf(organized), viewModel.events.upcoming)
    assertEquals(true, viewModel.events.isOffline)
  }

  /** A view model whose registrations never answer, like a server that stays silent. */
  private fun viewModelWithSilentRegistrations(): MyEventsViewModel {
    val silent =
        object : RegistrationRepository by registrations {
          override fun observeMyRegistrations(): Flow<MyRegistrationsResult> = flow {
            awaitCancellation()
          }
        }
    return MyEventsViewModel(ChosenEventsRepository(silent, events), registrations, network, clock)
  }

  private companion object {
    const val STUDENT = "test-student"
  }
}
