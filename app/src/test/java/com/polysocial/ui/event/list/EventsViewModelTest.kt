// Contributors: Claude (wrote these tests, #105).
package com.polysocial.ui.event.list

import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EventsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val repository = FakeEventRepository()
  private val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
  private val today = LocalDate.of(2026, 1, 1)

  // Starts at 13:00 in Lausanne, so these start later today and tomorrow there.
  private val laterToday = validEvent(title = "Later today", startTime = TEST_NOW.plusSeconds(3600))
  private val tomorrow =
      validEvent(title = "Tomorrow", startTime = TEST_NOW.plus(Duration.ofDays(1)))

  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private fun createViewModel() = EventsViewModel(repository, network, clock)

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  private fun EventsViewModel.state() = uiState.value

  @Test
  fun beforeTheFirstResult_isLoading() {
    repository.seed(tomorrow)

    val viewModel = createViewModel()

    assertEquals(EventsUiState.Loading, viewModel.state())
  }

  @Test
  fun upcomingEvents_areShownGroupedByDay() {
    repository.seed(tomorrow, laterToday)

    val viewModel = createViewModel()
    runPending()

    assertEquals(
        EventsUiState.Events(
            listOf(
                EventDaySection(today, listOf(laterToday)),
                EventDaySection(today.plusDays(1), listOf(tomorrow)),
            ),
            today = today,
            isOffline = false,
        ),
        viewModel.state(),
    )
  }

  @Test
  fun noUpcomingEvents_isEmpty() {
    val viewModel = createViewModel()
    runPending()

    assertEquals(EventsUiState.Empty(isOffline = false), viewModel.state())
  }

  @Test
  fun eventsFromTheOfflineCache_areMarkedOffline() {
    online = false
    repository.readFromCache = true
    repository.seed(tomorrow)

    val viewModel = createViewModel()
    runPending()

    assertEquals(
        EventsUiState.Events(
            listOf(EventDaySection(today.plusDays(1), listOf(tomorrow))),
            today = today,
            isOffline = true,
        ),
        viewModel.state(),
    )
  }

  @Test
  fun anEmptyOfflineCache_isEmptyAndMarkedOffline() {
    online = false
    repository.readFromCache = true

    val viewModel = createViewModel()
    runPending()

    assertEquals(EventsUiState.Empty(isOffline = true), viewModel.state())
  }

  // Firestore's first snapshot comes from the cache even online, until the server answers.
  @Test
  fun cachedEventsWhileOnline_areNotMarkedOffline() {
    repository.readFromCache = true
    repository.seed(tomorrow)

    val viewModel = createViewModel()
    runPending()

    assertEquals(
        EventsUiState.Events(
            listOf(EventDaySection(today.plusDays(1), listOf(tomorrow))),
            today = today,
            isOffline = false,
        ),
        viewModel.state(),
    )
  }

  @Test
  fun anEmptyCacheWhileOnline_isNotMarkedOffline() {
    repository.readFromCache = true

    val viewModel = createViewModel()
    runPending()

    assertEquals(EventsUiState.Empty(isOffline = false), viewModel.state())
  }

  @Test
  fun theList_followsChangesToTheEvents() {
    val viewModel = createViewModel()
    runPending()

    repository.seed(tomorrow)
    runPending()

    assertEquals(
        EventsUiState.Events(
            listOf(EventDaySection(today.plusDays(1), listOf(tomorrow))),
            today = today,
            isOffline = false,
        ),
        viewModel.state(),
    )
  }

  @Test
  fun aFailedLoad_isAnError() {
    repository.readFailure = true

    val viewModel = createViewModel()
    runPending()

    assertEquals(EventsUiState.Error, viewModel.state())
  }

  @Test
  fun retryAfterAnError_loadsTheEventsAgain() {
    repository.readFailure = true
    repository.seed(tomorrow)
    val viewModel = createViewModel()
    runPending()

    repository.readFailure = false
    viewModel.retry()

    assertEquals(EventsUiState.Loading, viewModel.state())
    runPending()
    assertEquals(
        EventsUiState.Events(
            listOf(EventDaySection(today.plusDays(1), listOf(tomorrow))),
            today = today,
            isOffline = false,
        ),
        viewModel.state(),
    )
  }

  @Test
  fun retryWhenThereIsNoError_changesNothing() {
    repository.seed(tomorrow)
    val viewModel = createViewModel()
    runPending()
    val loaded = viewModel.state()

    viewModel.retry()

    assertEquals(loaded, viewModel.state())
  }
}
