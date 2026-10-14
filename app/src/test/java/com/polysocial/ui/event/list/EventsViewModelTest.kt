// Contributors: Claude (wrote these tests, #105; search and filters, #115).
package com.polysocial.ui.event.list

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.event.EventCategory
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

  private fun createViewModel(savedState: SavedStateHandle = SavedStateHandle()) =
      EventsViewModel(repository, network, clock, savedState)

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  private fun EventsViewModel.state() = uiState.value.list

  @Test
  fun beforeTheFirstResult_isLoading() {
    repository.seed(tomorrow)

    val viewModel = createViewModel()

    assertEquals(EventsListState.Loading, viewModel.state())
  }

  @Test
  fun upcomingEvents_areShownGroupedByDay() {
    repository.seed(tomorrow, laterToday)

    val viewModel = createViewModel()
    runPending()

    assertEquals(
        EventsListState.Events(
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

    assertEquals(EventsListState.Empty(isOffline = false), viewModel.state())
  }

  @Test
  fun eventsFromTheOfflineCache_areMarkedOffline() {
    online = false
    repository.readFromCache = true
    repository.seed(tomorrow)

    val viewModel = createViewModel()
    runPending()

    assertEquals(
        EventsListState.Events(
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

    assertEquals(EventsListState.Empty(isOffline = true), viewModel.state())
  }

  // Firestore's first snapshot comes from the cache even online, until the server answers.
  @Test
  fun cachedEventsWhileOnline_areNotMarkedOffline() {
    repository.readFromCache = true
    repository.seed(tomorrow)

    val viewModel = createViewModel()
    runPending()

    assertEquals(
        EventsListState.Events(
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

    assertEquals(EventsListState.Empty(isOffline = false), viewModel.state())
  }

  @Test
  fun theList_followsChangesToTheEvents() {
    val viewModel = createViewModel()
    runPending()

    repository.seed(tomorrow)
    runPending()

    assertEquals(
        EventsListState.Events(
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

    assertEquals(EventsListState.Error, viewModel.state())
  }

  @Test
  fun retryAfterAnError_loadsTheEventsAgain() {
    repository.readFailure = true
    repository.seed(tomorrow)
    val viewModel = createViewModel()
    runPending()

    repository.readFailure = false
    viewModel.retry()

    assertEquals(EventsListState.Loading, viewModel.state())
    runPending()
    assertEquals(
        EventsListState.Events(
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

  // ---- Search and filters ----

  // Thursday 1 January 2026 is today; Sunday ends the week.
  private val party =
      validEvent(title = "Balélec warm-up", startTime = TEST_NOW.plusSeconds(2 * 3600))
          .copy(id = "party", category = EventCategory.PARTY)
  private val sunday =
      validEvent(title = "Sunday climbing", startTime = TEST_NOW.plus(Duration.ofDays(3)))
          .copy(id = "sunday", category = EventCategory.SPORTS)
  private val monday =
      validEvent(title = "Monday study", startTime = TEST_NOW.plus(Duration.ofDays(4)))
          .copy(id = "monday")

  private fun EventsViewModel.shownTitles() =
      (state() as EventsListState.Events).sections.flatMap { section ->
        section.events.map { it.title }
      }

  private fun loadedViewModel(savedState: SavedStateHandle = SavedStateHandle()): EventsViewModel {
    repository.seed(laterToday, party, sunday, monday)
    return createViewModel(savedState).also { runPending() }
  }

  @Test
  fun aSearch_narrowsTheList_andClearingItRestoresIt() {
    val viewModel = loadedViewModel()

    viewModel.setSearch("CLIMB")

    assertEquals("CLIMB", viewModel.uiState.value.selection.search)
    assertEquals(listOf("Sunday climbing"), viewModel.shownTitles())

    viewModel.setSearch("")

    assertEquals(
        listOf("Later today", "Balélec warm-up", "Sunday climbing", "Monday study"),
        viewModel.shownTitles(),
    )
  }

  @Test
  fun aCategory_keepsOnlyItsEvents_andTogglingItAgainRemovesIt() {
    val viewModel = loadedViewModel()

    viewModel.toggleCategory(EventCategory.PARTY)
    viewModel.toggleCategory(EventCategory.SPORTS)

    assertEquals(
        setOf(EventCategory.PARTY, EventCategory.SPORTS),
        viewModel.uiState.value.selection.categories,
    )
    assertEquals(listOf("Balélec warm-up", "Sunday climbing"), viewModel.shownTitles())

    viewModel.toggleCategory(EventCategory.PARTY)

    assertEquals(listOf("Sunday climbing"), viewModel.shownTitles())
  }

  @Test
  fun today_keepsOnlyTodaysEvents() {
    val viewModel = loadedViewModel()

    viewModel.selectDate(DateChoice.TODAY)

    assertEquals(listOf("Later today", "Balélec warm-up"), viewModel.shownTitles())
  }

  @Test
  fun thisWeek_runsUntilSunday() {
    val viewModel = loadedViewModel()

    viewModel.selectDate(DateChoice.THIS_WEEK)

    assertEquals(
        listOf("Later today", "Balélec warm-up", "Sunday climbing"),
        viewModel.shownTitles(),
    )
  }

  @Test
  fun noMatchingEvent_isNoMatch_andKeepsTheOfflineFlag() {
    online = false
    repository.readFromCache = true
    val viewModel = loadedViewModel()

    viewModel.setSearch("hackathon")

    assertEquals(EventsListState.NoMatch(isOffline = true), viewModel.state())
  }

  @Test
  fun noUpcomingEvents_staysEmpty_evenWithAFilter() {
    val viewModel = createViewModel()
    runPending()

    viewModel.toggleCategory(EventCategory.PARTY)

    assertEquals(EventsListState.Empty(isOffline = false), viewModel.state())
  }

  @Test
  fun clearingTheSearchAndFilters_showsEveryEventAgain() {
    val viewModel = loadedViewModel()
    viewModel.setSearch("study")
    viewModel.toggleCategory(EventCategory.PARTY)
    viewModel.selectDate(DateChoice.TODAY)
    assertEquals(EventsListState.NoMatch(isOffline = false), viewModel.state())

    viewModel.clearSearchAndFilters()

    assertEquals(EventSelection(), viewModel.uiState.value.selection)
    assertEquals(4, viewModel.shownTitles().size)
  }

  @Test
  fun clearingTheFilters_keepsTheSearch() {
    val viewModel = loadedViewModel()
    viewModel.setSearch("i")
    viewModel.toggleCategory(EventCategory.SPORTS)
    viewModel.selectDate(DateChoice.TODAY)

    viewModel.clearFilters()

    assertEquals(EventSelection(search = "i"), viewModel.uiState.value.selection)
    assertEquals(listOf("Sunday climbing"), viewModel.shownTitles())
  }

  @Test
  fun theSelection_survivesTheViewModelBeingRecreated() {
    val savedState = SavedStateHandle()
    val first = loadedViewModel(savedState)
    first.setSearch("s")
    first.toggleCategory(EventCategory.SPORTS)
    first.selectDate(DateChoice.THIS_WEEK)

    val second = createViewModel(savedState)
    runPending()

    assertEquals(
        EventSelection("s", setOf(EventCategory.SPORTS), DateChoice.THIS_WEEK),
        second.uiState.value.selection,
    )
    assertEquals(listOf("Sunday climbing"), second.shownTitles())
  }

  @Test
  fun anUnknownSavedValue_isIgnored() {
    val savedState =
        SavedStateHandle(
            mapOf(
                "events_categories" to arrayListOf("PARTY", "CONCERT"),
                "events_date" to "NEXT_YEAR",
            )
        )

    val viewModel = loadedViewModel(savedState)

    assertEquals(
        EventSelection(categories = setOf(EventCategory.PARTY)),
        viewModel.uiState.value.selection,
    )
  }

  @Test
  fun eventsThatArriveLater_areFilteredToo() {
    val viewModel = createViewModel()
    runPending()
    viewModel.toggleCategory(EventCategory.SPORTS)

    repository.seed(party, sunday)
    runPending()

    assertEquals(listOf("Sunday climbing"), viewModel.shownTitles())
  }

  @Test
  fun aSelectionWhileLoading_isKept_andAppliedOnceTheEventsArrive() {
    repository.seed(party, sunday)
    val viewModel = createViewModel()

    viewModel.toggleCategory(EventCategory.PARTY)

    assertEquals(EventsListState.Loading, viewModel.state())
    runPending()
    assertEquals(listOf("Balélec warm-up"), viewModel.shownTitles())
  }

  @Test
  fun aSelectionAfterAnError_keepsTheError_andRetryAppliesIt() {
    repository.readFailure = true
    repository.seed(party, sunday)
    val viewModel = createViewModel()
    runPending()

    viewModel.toggleCategory(EventCategory.SPORTS)

    assertEquals(EventsListState.Error, viewModel.state())
    repository.readFailure = false
    viewModel.retry()
    runPending()
    assertEquals(listOf("Sunday climbing"), viewModel.shownTitles())
  }
}
