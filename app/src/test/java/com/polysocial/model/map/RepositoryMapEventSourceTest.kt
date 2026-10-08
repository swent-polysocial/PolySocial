// Contributors: OpenAI Codex (repository-to-map stream integration tests for #50).
package com.polysocial.model.map

import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.PublicEventsResult
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.ui.map.MAP_TEST_CLOCK
import com.polysocial.model.location.FakeLocationService
import com.polysocial.ui.map.MapContentStatus
import com.polysocial.ui.map.MapViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RepositoryMapEventSourceTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun seededRepositorySuppliesOnlyUpcomingPublicMarkersInStartOrder() = runTest {
    val sooner = validEvent().copy(id = "sooner")
    val later = validEvent(startTime = TEST_NOW.plus(Duration.ofDays(13))).copy(id = "later")
    val repository = FakeEventRepository()
    repository.seed(
        later,
        sooner.copy(id = "private", isPrivate = true),
        validEvent(startTime = TEST_NOW.minusSeconds(1)).copy(id = "started"),
        validEvent(startTime = TEST_NOW.plus(Duration.ofDays(14))).copy(id = "outside"),
        sooner,
    )

    assertEquals(
        MapEventResult.Events(listOf(sooner, later)),
        RepositoryMapEventSource(repository).observePublicUpcomingEvents().first(),
    )
  }

  @Test
  fun newlyCreatedEventReachesTheMapWithoutRetry() = runTest {
    val repository = FakeEventRepository()
    val viewModel = MapViewModel(RepositoryMapEventSource(repository), FakeLocationService(), MAP_TEST_CLOCK)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(MapContentStatus.EMPTY, viewModel.uiState.value.status)

    val saved = repository.createEvent(validEvent()) as CreateEventResult.Created
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(MapContentStatus.READY, viewModel.uiState.value.status)
    assertEquals(listOf(saved.eventId), viewModel.uiState.value.events.map { it.id })
    viewModel.selectEvent(saved.eventId)
    assertEquals("Study session", viewModel.uiState.value.selectedEvent?.title)
  }

  @Test
  fun cachedEventsRemainUsableOffline() = runTest {
    val cached = validEvent().copy(id = "cached")
    val repository = FakeEventRepository().apply { readFromCache = true }
    repository.seed(cached)
    val viewModel = MapViewModel(RepositoryMapEventSource(repository), FakeLocationService(), MAP_TEST_CLOCK)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(MapContentStatus.READY, viewModel.uiState.value.status)
    assertEquals(listOf(cached), viewModel.uiState.value.events)
    viewModel.selectEvent(cached.id)
    assertEquals(cached, viewModel.uiState.value.selectedEvent)
  }

  @Test
  fun repositoryErrorTerminatesTheAdaptedStream() = runTest {
    val repository = FakeEventRepository().apply { readFailure = true }

    assertEquals(
        listOf(MapEventResult.Error),
        RepositoryMapEventSource(repository).observePublicUpcomingEvents().toList(),
    )
  }

  @Test
  fun retryAfterTerminalErrorStartsAFreshRepositoryCollection() = runTest {
    val repository = FakeEventRepository().apply { readFailure = true }
    val viewModel = MapViewModel(RepositoryMapEventSource(repository), FakeLocationService(), MAP_TEST_CLOCK)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(MapContentStatus.ERROR, viewModel.uiState.value.status)

    val event = validEvent().copy(id = "recovered")
    repository.seed(event)
    repository.readFailure = false
    viewModel.retry()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(MapContentStatus.READY, viewModel.uiState.value.status)
    assertEquals(listOf(event), viewModel.uiState.value.events)
  }

  @Test
  fun cancellingMapCollectionCancelsTheRepositoryListenerFlow() = runTest {
    var listening = false
    val repository =
        object : EventRepository {
          override suspend fun createEvent(event: Event): CreateEventResult =
              error("This test only observes events")

          override fun getUpcomingPublicEvents(windowDays: Int): Flow<PublicEventsResult> = flow {
            listening = true
            try {
              awaitCancellation()
            } finally {
              listening = false
            }
          }
        }
    val collecting = launch {
      RepositoryMapEventSource(repository).observePublicUpcomingEvents().collect {}
    }
    testScheduler.runCurrent()
    assertTrue(listening)

    collecting.cancel()
    collecting.join()

    assertEquals(false, listening)
  }
}
