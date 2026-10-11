// Contributors: Claude (wrote these tests of the event detail ViewModel, #112).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

  private fun viewModel(args: Map<String, Any?> = mapOf("eventId" to "event-1")) =
      EventDetailViewModel(SavedStateHandle(args), repository, network, clock)

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
  fun notCachedWhileOnline_keepsLoadingUntilTheServerAnswers() = runTest {
    repository.readFromCache = true
    val vm = viewModel()
    val states = observe(vm)

    assertEquals(listOf(EventDetailUiState.Loading), states)
  }
}
