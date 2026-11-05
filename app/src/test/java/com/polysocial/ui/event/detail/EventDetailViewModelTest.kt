// Contributors: Claude (wrote these tests of the event detail ViewModel, #112; the server timeout
// and Retry, #194; Edit and Cancel event for organizers, #131; the association names dependency,
// #128; the manage event's "Going" count, #131).
// Contributors: Claude (Opus 5.5, Anthropic) whether the event has ended, #233.
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.DeleteEventResult
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.event.withCreator
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.AttendeeCountResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  // A zone that is neither UTC nor this machine's default, so a ViewModel that ignored the clock's
  // zone would show other times.
  private val tokyo = ZoneId.of("Asia/Tokyo")
  private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), tokyo)
  private val repository = FakeEventRepository()
  private var online = true
  private val auth = FakeAuthRepository()
  private val registrations = FakeRegistrationRepository()
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  /** Saturday 10 October 2026, 22:00 to 02:00 in Tokyo (13:00 to 17:00 UTC, the same UTC day). */
  private val event =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
          )
          .copy(id = "event-1", title = "Late study session")

  private val loadedOnline =
      EventDetailUiState.Loaded(
          EventDetails(
              event = event,
              date = LocalDate.of(2026, 10, 10),
              startTime = LocalTime.of(22, 0),
              endTime = LocalTime.of(2, 0),
              endDayOffset = 1,
          ),
          isOffline = false,
      )

  private fun viewModel(
      args: Map<String, Any?> = mapOf("eventId" to "event-1"),
      repository: EventRepository = this.repository,
  ) =
      EventDetailViewModel(
          SavedStateHandle(args),
          repository,
          network,
          auth,
          clock,
          FakeAssociationNames(),
          registrations,
      )

  /** [repository], except that [getEvent][EventRepository.getEvent] returns [results]. */
  private fun repositoryAnswering(results: Flow<EventResult>): EventRepository =
      object : EventRepository by repository {
        override fun getEvent(id: String) = results
      }

  /** Collects [vm]'s state like the screen does, and returns every state it showed. */
  private fun TestScope.observe(vm: EventDetailViewModel) =
      mutableListOf<EventDetailUiState>().also { states ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          vm.uiState.collect { states += it }
        }
        runCurrent()
      }

  @Test
  fun beforeTheEventIsRead_theStateIsLoading() = runTest {
    repository.seed(event)
    val vm = viewModel()

    assertEquals(EventDetailUiState.Loading, vm.uiState.value)
    val states = observe(vm)

    assertEquals(listOf(EventDetailUiState.Loading, loadedOnline), states)
  }

  @Test
  fun anEventFromTheCacheWhileOffline_isLoadedAndMarkedOffline() = runTest {
    online = false
    repository.readFromCache = true
    repository.seed(event)
    val vm = viewModel()
    observe(vm)

    assertEquals(loadedOnline.copy(isOffline = true), vm.uiState.value)
  }

  @Test
  fun aChangeToTheEvent_showsLive() = runTest {
    repository.seed(event)
    val vm = viewModel()
    observe(vm)

    val moved =
        event.copy(title = "Moved to Rolex", endTime = Instant.parse("2026-10-10T14:00:00Z"))
    repository.remove("event-1")
    repository.seed(moved)
    runCurrent()

    val state = vm.uiState.value as EventDetailUiState.Loaded
    assertEquals("Moved to Rolex", state.details.event.title)
    assertEquals(LocalTime.of(23, 0), state.details.endTime)
    assertEquals(0, state.details.endDayOffset)
  }

  @Test
  fun deletingTheEvent_showsNotFound() = runTest {
    repository.seed(event)
    val vm = viewModel()
    val states = observe(vm)

    repository.remove("event-1")
    runCurrent()

    assertEquals(
        listOf(EventDetailUiState.Loading, loadedOnline, EventDetailUiState.NotFound),
        states,
    )
  }

  @Test
  fun anEventTheRulesDeny_isNotFound() = runTest {
    repository.seed(event)
    repository.deniedIds += "event-1"
    val vm = viewModel()
    observe(vm)

    assertEquals(EventDetailUiState.NotFound, vm.uiState.value)
  }

  @Test
  fun anUnknownEvent_isNotFound() = runTest {
    repository.seed(event)
    val vm = viewModel(mapOf("eventId" to "event-2"))
    observe(vm)

    assertEquals(EventDetailUiState.NotFound, vm.uiState.value)
  }

  @Test
  fun aMissingNavigationArgument_isNotFoundEvenOffline() = runTest {
    online = false
    repository.readFromCache = true
    repository.seed(event)
    val vm = viewModel(emptyMap())
    observe(vm)

    assertEquals(EventDetailUiState.NotFound, vm.uiState.value)
  }

  @Test
  fun offlineAndNeverLoaded_showsOfflineNotSavedThenTheEventOnceOnline() = runTest {
    online = false
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)
    assertEquals(EventDetailUiState.OfflineNotSaved, vm.uiState.value)

    online = true
    repository.readFromCache = false
    repository.seed(event)
    runCurrent()

    assertEquals(
        listOf(EventDetailUiState.Loading, EventDetailUiState.OfflineNotSaved, loadedOnline),
        states,
    )
  }

  @Test
  fun aCachedEventWhileOnline_isNotMarkedOffline() = runTest {
    // Opened from the map or the Events tab, the event is cached, so Firestore's first snapshot
    // comes from the cache even though the device is online.
    repository.readFromCache = true
    repository.seed(event)
    val vm = viewModel()
    observe(vm)

    assertEquals(loadedOnline, vm.uiState.value)
  }

  @Test
  fun notCachedWhileOnline_loadsAtFirst() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)

    assertEquals(listOf(EventDetailUiState.Loading), states)
  }

  private val timeout = EventDetailViewModel.SERVER_ANSWER_TIMEOUT_MILLIS

  /** Lets [millis] of virtual time pass, running everything due by then. */
  private fun TestScope.wait(millis: Long) {
    advanceTimeBy(millis)
    runCurrent()
  }

  /**
   * Stores [event] the way a server that is reachable again would serve it to a new read, without
   * notifying reads already listening (they never hear back from an unreachable server).
   */
  private fun serverHasTheEventForNewReads() {
    repository.readFromCache = false
    repository.events += event
  }

  @Test
  fun notCachedWhileOnline_showsOfflineNotSavedOnceTheServerTimesOut() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)

    wait(timeout - 1)
    assertEquals(EventDetailUiState.Loading, vm.uiState.value)
    wait(1)

    assertEquals(listOf(EventDetailUiState.Loading, EventDetailUiState.OfflineNotSaved), states)
  }

  @Test
  fun anEventFromTheServerBeforeTheTimeout_cancelsIt() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)
    wait(timeout - 1)

    repository.readFromCache = false
    repository.seed(event)
    runCurrent()
    wait(timeout)

    assertEquals(listOf(EventDetailUiState.Loading, loadedOnline), states)
  }

  @Test
  fun notFoundFromTheServerBeforeTheTimeout_cancelsIt() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)
    wait(timeout - 1)

    // The server answers that the event doesn't exist.
    repository.readFromCache = false
    repository.remove("event-1")
    runCurrent()
    wait(timeout)

    assertEquals(listOf(EventDetailUiState.Loading, EventDetailUiState.NotFound), states)
  }

  @Test
  fun aServerAnswerAfterTheTimeout_stillShowsTheEvent() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)
    wait(timeout)

    repository.readFromCache = false
    repository.seed(event)
    runCurrent()

    assertEquals(
        listOf(EventDetailUiState.Loading, EventDetailUiState.OfflineNotSaved, loadedOnline),
        states,
    )
  }

  @Test
  fun retryAfterTheTimeout_readsAgainAndShowsTheEvent() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)
    wait(timeout)
    serverHasTheEventForNewReads()

    vm.retry()
    runCurrent()

    assertEquals(
        listOf(
            EventDetailUiState.Loading,
            EventDetailUiState.OfflineNotSaved,
            EventDetailUiState.Loading,
            loadedOnline,
        ),
        states,
    )
  }

  @Test
  fun retryWhileTheServerStillDoesntAnswer_loadsThenTimesOutAgain() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    observe(vm)
    wait(timeout)

    vm.retry()
    runCurrent()
    assertEquals(EventDetailUiState.Loading, vm.uiState.value)
    wait(timeout - 1)
    assertEquals(EventDetailUiState.Loading, vm.uiState.value)
    wait(1)

    assertEquals(EventDetailUiState.OfflineNotSaved, vm.uiState.value)
  }

  @Test
  fun retryWhenOfflineAndNeverLoaded_readsAgain() = runTest {
    online = false
    repository.readFromCache = true
    val vm = viewModel()
    observe(vm)
    assertEquals(EventDetailUiState.OfflineNotSaved, vm.uiState.value)

    online = true
    serverHasTheEventForNewReads()
    vm.retry()
    runCurrent()

    assertEquals(loadedOnline, vm.uiState.value)
  }

  @Test
  fun noAnswerAtAllWhileOnline_showsOfflineNotSavedOnceTheServerTimesOut() = runTest {
    // Firestore emits nothing when the connection died silently while it thought it was online.
    val vm = viewModel(repository = repositoryAnswering(flow { awaitCancellation() }))
    val states = observe(vm)

    wait(timeout - 1)
    assertEquals(EventDetailUiState.Loading, vm.uiState.value)
    wait(1)

    assertEquals(listOf(EventDetailUiState.Loading, EventDetailUiState.OfflineNotSaved), states)
  }

  @Test
  fun notCachedArrivingLate_doesNotRestartTheTimeout() = runTest {
    // Firestore reports the missing event from the cache only once it decides it is offline.
    val results = MutableSharedFlow<EventResult>(extraBufferCapacity = 1)
    val vm = viewModel(repository = repositoryAnswering(results))
    observe(vm)
    wait(9_000)

    results.tryEmit(EventResult.NotCached)
    runCurrent()
    assertEquals(EventDetailUiState.Loading, vm.uiState.value)
    wait(timeout - 9_000)

    assertEquals(EventDetailUiState.OfflineNotSaved, vm.uiState.value)
  }

  @Test
  fun aSavedEventOpenedOffline_loadsUntilTheCacheAnswers() = runTest {
    // Firestore answers asynchronously, even from the cache.
    online = false
    val results = MutableSharedFlow<EventResult>(extraBufferCapacity = 1)
    val vm = viewModel(repository = repositoryAnswering(results))
    val states = observe(vm)
    assertEquals(listOf(EventDetailUiState.Loading), states)

    results.tryEmit(EventResult.Found(event, fromCache = true))
    runCurrent()

    assertEquals(listOf(EventDetailUiState.Loading, loadedOnline.copy(isOffline = true)), states)
  }

  /** [event], organized by the signed-in user ("organizer", also the fake repository's user). */
  private fun seedOwnEvent() {
    repository.currentUid = "organizer"
    auth.user = AuthUser("organizer", "organizer@example.com", isEmailVerified = true)
    repository.seed(event.withCreator("organizer", isVerifiedAssociation = false))
  }

  private fun EventDetailUiState.isOrganizer() = (this as EventDetailUiState.Loaded).isOrganizer

  @Test
  fun anOrganizer_isToldTheyOrganizeTheEvent_andNobodyElse() = runTest {
    seedOwnEvent()
    val vm = viewModel()
    observe(vm)
    assertTrue(vm.uiState.value.isOrganizer())

    auth.user = AuthUser("another-student", "student@example.com", isEmailVerified = true)
    val other = viewModel()
    observe(other)
    assertFalse(other.uiState.value.isOrganizer())

    auth.user = null
    val signedOut = viewModel()
    observe(signedOut)
    assertFalse(signedOut.uiState.value.isOrganizer())
  }

  @Test
  fun cancelling_asksToConfirmThenDeletesTheEventAndIsCancelled() = runTest {
    seedOwnEvent()
    val vm = viewModel()
    val states = observe(vm)
    repository.pending = CompletableDeferred()

    // Nothing to confirm before Cancel event is tapped.
    vm.onConfirmCancel()
    assertEquals(CancelEventState.Hidden, vm.cancelState.value)
    vm.onCancelEvent()
    assertEquals(CancelEventState.Confirming, vm.cancelState.value)
    vm.onConfirmCancel()
    runCurrent()
    assertEquals(CancelEventState.Cancelling, vm.cancelState.value)

    repository.pending!!.complete(Unit)
    runCurrent()

    assertEquals(CancelEventState.Cancelled, vm.cancelState.value)
    assertEquals(listOf("event-1"), repository.deleteRequests)
    assertTrue(repository.events.isEmpty())
    // The live read reports the deleted event missing, but the screen keeps it until it closes.
    assertTrue(states.none { it == EventDetailUiState.NotFound })
    assertTrue(vm.uiState.value is EventDetailUiState.Loaded)
  }

  @Test
  fun keepEvent_hidesTheConfirmationAndDeletesNothing() = runTest {
    seedOwnEvent()
    val vm = viewModel()
    observe(vm)

    vm.onCancelEvent()
    vm.onKeepEvent()
    runCurrent()

    assertEquals(CancelEventState.Hidden, vm.cancelState.value)
    assertTrue(repository.deleteRequests.isEmpty())
  }

  @Test
  fun whileCancelling_theConfirmationCantBeDismissedOrSentAgain() = runTest {
    seedOwnEvent()
    val vm = viewModel()
    observe(vm)
    repository.pending = CompletableDeferred()
    vm.onCancelEvent()
    vm.onConfirmCancel()
    runCurrent()

    vm.onKeepEvent()
    vm.onConfirmCancel()
    vm.onCancelEvent()
    runCurrent()

    assertEquals(CancelEventState.Cancelling, vm.cancelState.value)
    assertEquals(listOf("event-1"), repository.deleteRequests)
  }

  @Test
  fun cancellingOffline_keepsTheEventAndOffersTryAgain() = runTest {
    seedOwnEvent()
    val vm = viewModel()
    observe(vm)
    repository.deleteFailure = DeleteEventResult.NetworkError
    vm.onCancelEvent()
    vm.onConfirmCancel()
    runCurrent()

    assertEquals(
        CancelEventState.Failed(CancelEventState.Failed.Reason.OFFLINE),
        vm.cancelState.value,
    )
    assertEquals(1, repository.events.size)

    // Try again, back online.
    repository.deleteFailure = null
    vm.onConfirmCancel()
    runCurrent()

    assertEquals(CancelEventState.Cancelled, vm.cancelState.value)
    assertTrue(repository.events.isEmpty())
  }

  @Test
  fun aDeleteTheRepositoryRefuses_isNeverReportedAsCancelled() = runTest {
    seedOwnEvent()
    for (refusal in
        listOf(
            DeleteEventResult.NotOrganizer,
            DeleteEventResult.NotFound,
            DeleteEventResult.UnexpectedError,
        )) {
      val vm = viewModel()
      observe(vm)
      repository.deleteFailure = refusal

      vm.onCancelEvent()
      vm.onConfirmCancel()
      runCurrent()

      assertEquals(
          "$refusal",
          CancelEventState.Failed(CancelEventState.Failed.Reason.UNEXPECTED),
          vm.cancelState.value,
      )
      assertEquals(1, repository.events.size)
    }
  }

  // The association's manage event (#131)

  /** Signs in the verified association that organizes [event], with 3 students going. */
  private fun seedManagedEvent() {
    repository.currentUid = "association"
    auth.user = AuthUser("association", "association@example.com", isEmailVerified = true)
    repository.seed(event.withCreator("association", isVerifiedAssociation = true))
    listOf("s1", "s2", "s3").forEach { registrations.seedRegistration("event-1", it) }
  }

  private fun EventDetailUiState.loaded() = this as EventDetailUiState.Loaded

  @Test
  fun anAssociationsOwnEvent_isManaged_andShowsHowManyAreGoing() = runTest {
    seedManagedEvent()
    val vm = viewModel()
    observe(vm)

    val state = vm.uiState.value.loaded()
    assertTrue(state.isManaged)
    assertEquals(GoingCount.Count(3), state.going)
  }

  @Test
  fun whileCounting_theEventShowsWithTheCountLoading() = runTest {
    seedManagedEvent()
    val answer = CompletableDeferred<AttendeeCountResult>()
    val slow =
        object : RegistrationRepository by registrations {
          override suspend fun countAttendees(eventId: String) = answer.await()
        }
    val vm =
        EventDetailViewModel(
            SavedStateHandle(mapOf("eventId" to "event-1")),
            repository,
            network,
            auth,
            clock,
            FakeAssociationNames(),
            slow,
        )
    observe(vm)
    assertEquals(GoingCount.Loading, vm.uiState.value.loaded().going)

    answer.complete(AttendeeCountResult.Count(12))
    runCurrent()

    assertEquals(GoingCount.Count(12), vm.uiState.value.loaded().going)
  }

  @Test
  fun aStudentsOwnEvent_isNotManaged_andCountsNobody() = runTest {
    seedOwnEvent()
    val vm = viewModel()
    observe(vm)

    val state = vm.uiState.value.loaded()
    assertTrue(state.isOrganizer)
    assertFalse(state.isManaged)
    assertEquals(null, state.going)
    assertEquals(0, registrations.countCalls)
  }

  @Test
  fun anAssociationEventSomeoneElseOrganizes_isNotManaged() = runTest {
    seedManagedEvent()
    auth.user = AuthUser("student", "student@epfl.ch", isEmailVerified = true)
    val vm = viewModel()
    observe(vm)

    assertFalse(vm.uiState.value.loaded().isManaged)
    assertEquals(0, registrations.countCalls)
  }

  @Test
  fun aCountThatFails_isUnavailable_andTappingItCountsAgain() = runTest {
    seedManagedEvent()
    registrations.countFailure = AttendeeCountResult.NetworkError
    val vm = viewModel()
    observe(vm)
    assertEquals(GoingCount.Unavailable, vm.uiState.value.loaded().going)

    registrations.countFailure = null
    vm.retryGoingCount()
    runCurrent()

    assertEquals(GoingCount.Count(3), vm.uiState.value.loaded().going)
    assertEquals(2, registrations.countCalls)
  }

  @Test
  fun anUnexpectedCountFailure_isUnavailableToo() = runTest {
    seedManagedEvent()
    registrations.countFailure = AttendeeCountResult.UnexpectedError
    val vm = viewModel()
    observe(vm)

    assertEquals(GoingCount.Unavailable, vm.uiState.value.loaded().going)
  }

  @Test
  fun retryingACountThatWorked_countsNothingAgain() = runTest {
    seedManagedEvent()
    val vm = viewModel()
    observe(vm)

    vm.retryGoingCount()
    runCurrent()

    assertEquals(1, registrations.countCalls)
  }

  @Test
  fun aChangeToTheManagedEvent_doesNotCountAgain() = runTest {
    seedManagedEvent()
    val vm = viewModel()
    observe(vm)

    repository.updateEvent(vm.uiState.value.loaded().details.event.copy(title = "Renamed"))
    runCurrent()

    assertEquals("Renamed", vm.uiState.value.loaded().details.event.title)
    assertEquals(GoingCount.Count(3), vm.uiState.value.loaded().going)
    assertEquals(1, registrations.countCalls)
  }

  @Test
  fun anEventRunningNow_isLive_andOneLaterIsNot() = runTest {
    // The clock reads 7 October 2026, 10:00 UTC.
    repository.seed(
        event.copy(
            id = "running",
            startTime = Instant.parse("2026-10-07T09:00:00Z"),
            endTime = Instant.parse("2026-10-07T11:00:00Z"),
        )
    )
    val running = viewModel(args = mapOf("eventId" to "running"))
    observe(running)
    assertTrue(running.uiState.value.loaded().isLive)

    repository.seed(event)
    val later = viewModel()
    observe(later)
    assertFalse(later.uiState.value.loaded().isLive)
  }

  @Test
  fun anEventThatHasEnded_hasEnded_andOneRunningNowHasNot() = runTest {
    // The clock reads 7 October 2026, 10:00 UTC.
    repository.seed(
        event.copy(
            id = "ended",
            startTime = Instant.parse("2026-10-07T08:00:00Z"),
            endTime = Instant.parse("2026-10-07T09:00:00Z"),
        )
    )
    val ended = viewModel(args = mapOf("eventId" to "ended"))
    observe(ended)
    assertTrue(ended.uiState.value.loaded().hasEnded)

    repository.seed(
        event.copy(
            id = "running",
            startTime = Instant.parse("2026-10-07T09:00:00Z"),
            endTime = Instant.parse("2026-10-07T11:00:00Z"),
        )
    )
    val running = viewModel(args = mapOf("eventId" to "running"))
    observe(running)
    assertFalse(running.uiState.value.loaded().hasEnded)
  }
}
