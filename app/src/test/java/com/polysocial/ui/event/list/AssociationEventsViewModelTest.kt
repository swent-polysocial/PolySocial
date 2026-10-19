// Contributors: Claude (wrote these tests, #132; keeping the split current, #132 review).
package com.polysocial.ui.event.list

import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.event.withCreator
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AssociationEventsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val repository = FakeEventRepository(currentUid = "association")
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

  private fun own(id: String, startsIn: Duration) =
      validEvent(startTime = TEST_NOW.plus(startsIn))
          .withCreator("association", isVerifiedAssociation = true)
          .copy(id = id)

  private val tomorrow = own("tomorrow", Duration.ofDays(1))
  private val nextWeek = own("nextWeek", Duration.ofDays(7))
  private val lastWeek = own("lastWeek", Duration.ofDays(-7))

  private fun createViewModel() =
      AssociationEventsViewModel(repository, network, clock).also { runPending() }

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  @Test
  fun beforeTheFirstResult_isLoading() {
    val viewModel = AssociationEventsViewModel(repository, network, clock)

    assertEquals(AssociationEventsState.Loading, viewModel.state.value)
  }

  @Test
  fun theOwnEvents_areSplitIntoUpcomingAndPast() {
    // Another association's event and a student's own event are not this association's.
    val someoneElses =
        validEvent().withCreator("other", isVerifiedAssociation = true).copy(id = "other")
    repository.seed(nextWeek, lastWeek, tomorrow, someoneElses)

    val viewModel = createViewModel()

    assertEquals(
        AssociationEventsState.Events(
            upcoming = listOf(tomorrow, nextWeek),
            past = listOf(lastWeek),
            now = TEST_NOW,
            isOffline = false,
        ),
        viewModel.state.value,
    )
  }

  @Test
  fun aNewEvent_showsWithoutReloading() {
    val viewModel = createViewModel()

    repository.seed(tomorrow)
    runPending()

    assertEquals(
        listOf(tomorrow),
        (viewModel.state.value as AssociationEventsState.Events).upcoming,
    )
  }

  @Test
  fun cachedEvents_areOfflineOnlyWhenTheDeviceIs() {
    repository.seed(tomorrow)
    repository.readFromCache = true

    val online = createViewModel().state.value as AssociationEventsState.Events
    this.online = false
    val offline = createViewModel().state.value as AssociationEventsState.Events

    assertEquals(false, online.isOffline)
    assertEquals(true, offline.isOffline)
  }

  @Test
  fun aFailedRead_isAnErrorThatRetryRecoversFrom() {
    repository.seed(tomorrow)
    repository.ownEventsFailure = true
    val viewModel = createViewModel()
    assertEquals(AssociationEventsState.Error, viewModel.state.value)

    repository.ownEventsFailure = false
    viewModel.retry()
    runPending()

    assertEquals(
        listOf(tomorrow),
        (viewModel.state.value as AssociationEventsState.Events).upcoming,
    )
  }

  @Test
  fun retry_doesNothingOnceLoaded() {
    val viewModel = createViewModel()
    val loaded = viewModel.state.value

    repository.ownEventsFailure = true
    viewModel.retry()
    runPending()

    assertEquals(loaded, viewModel.state.value)
  }

  /** Started an hour ago, so it is live until it ends in an hour ([validEvent] lasts 2 h). */
  private val running = own("running", Duration.ofHours(-1))

  @Test
  fun refresh_splitsTheLastEventsAgainAtTheCurrentTime() {
    repository.seed(running, tomorrow)
    val viewModel = createViewModel()
    val later = TEST_NOW.plus(Duration.ofHours(2))

    clock.instant = later
    viewModel.refresh()

    assertEquals(
        AssociationEventsState.Events(
            upcoming = listOf(tomorrow),
            past = listOf(running),
            now = later,
            isOffline = false,
        ),
        viewModel.state.value,
    )
  }

  @Test
  fun refresh_beforeTheEventsOrAfterAnError_changesNothing() {
    val viewModel = AssociationEventsViewModel(repository, network, clock)
    viewModel.refresh()
    assertEquals(AssociationEventsState.Loading, viewModel.state.value)

    repository.ownEventsFailure = true
    val failed = createViewModel()
    failed.refresh()
    assertEquals(AssociationEventsState.Error, failed.state.value)
  }

  @Test
  fun keepSplitCurrent_movesAnEventThatEndsToPastWithinAMinute() =
      runTest(mainDispatcherRule.dispatcher) {
        repository.seed(running)
        val viewModel = createViewModel()
        val keeping = launch { viewModel.keepSplitCurrent() }
        runCurrent()

        // The event ends at TEST_NOW + 1 h; nothing new comes from Firestore.
        clock.instant = TEST_NOW.plus(Duration.ofMinutes(60)).plusSeconds(30)
        advanceTimeBy(AssociationEventsViewModel.REFRESH_INTERVAL.inWholeMilliseconds - 1)
        runCurrent()
        assertEquals(
            listOf(running),
            (viewModel.state.value as AssociationEventsState.Events).upcoming,
        )

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(running), (viewModel.state.value as AssociationEventsState.Events).past)
        keeping.cancel()
      }
}
