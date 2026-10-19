// Contributors: Claude (wrote these tests, #132).
package com.polysocial.ui.event.list

import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.event.withCreator
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AssociationEventsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val repository = FakeEventRepository(currentUid = "association")
  private val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
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
}
