// Contributors: Claude (state of the Events tab's list, #105; offline only when the device is,
// after review; search and filters, #115).
package com.polysocial.ui.event.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.PublicEventsResult
import com.polysocial.model.event.filterEvents
import com.polysocial.model.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Events tab shows: its list, and the search and filters it is narrowed by. */
data class EventsUiState(
    val list: EventsListState = EventsListState.Loading,
    val selection: EventSelection = EventSelection(),
)

/** The Events tab's list. */
sealed interface EventsListState {
  /** The first result has not arrived yet. */
  data object Loading : EventsListState

  /** There are no upcoming public events. */
  data class Empty(val isOffline: Boolean) : EventsListState

  /** There are upcoming public events, but none match the search and filters. */
  data class NoMatch(val isOffline: Boolean) : EventsListState

  /**
   * The upcoming public events that match the search and filters, grouped by day. [today] is the
   * current date, so the screen can name today's and tomorrow's sections.
   */
  data class Events(
      val sections: List<EventDaySection>,
      val today: LocalDate,
      val isOffline: Boolean,
  ) : EventsListState

  /** The events could not be loaded; the screen offers Retry. */
  data object Error : EventsListState
}

/**
 * Observes the upcoming public events from [EventRepository] and groups the ones matching the
 * student's [EventSelection] by day for the Events tab. The list updates whenever the events or the
 * selection change; filtering runs on the loaded events (`filterEvents`), so it needs no new
 * request and works offline. `isOffline` is true when the events come from the offline cache and
 * [network] says the device is offline, so the screen can say they may be out of date. The cache
 * alone isn't enough: Firestore's first snapshot comes from it even online, until the server
 * answers.
 *
 * The selection is kept in [savedState], so it survives rotation and the process being recreated.
 *
 * After an error the repository stops emitting, so the list stays in [EventsListState.Error] until
 * [retry].
 *
 * @param clock gives the current date and time zone for the day sections and the date filters, so
 *   tests can fix them.
 */
@HiltViewModel
class EventsViewModel
@Inject
constructor(
    private val repository: EventRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val savedState: SavedStateHandle,
) : ViewModel() {
  private val _uiState = MutableStateFlow(EventsUiState(selection = restoreSelection()))
  val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

  /** The time zone of the day headings, in which the screen also shows the start times. */
  val zone: ZoneId
    get() = clock.zone

  /** The events last loaded; null while loading or after an error. */
  private var loaded: Loaded? = null
  private var observation: Job? = null

  init {
    observeEvents()
  }

  /** Loads the events again after an error. Does nothing in any other state. */
  fun retry() {
    if (_uiState.value.list != EventsListState.Error) return
    _uiState.value = _uiState.value.copy(list = EventsListState.Loading)
    observeEvents()
  }

  /** Searches the event titles for [search]. */
  fun setSearch(search: String) = select(selection().copy(search = search))

  /** Adds [category] to the selected categories, or removes it if it was selected. */
  fun toggleCategory(category: EventCategory) {
    val categories = selection().categories
    select(
        selection()
            .copy(
                categories =
                    if (category in categories) categories - category else categories + category
            )
    )
  }

  /** Keeps only the events starting on [date]. */
  fun selectDate(date: DateChoice) = select(selection().copy(date = date))

  /** Clears every filter but keeps the search, like Figma's *All* chip. */
  fun clearFilters() = select(EventSelection(search = selection().search))

  /** Clears the search and every filter, showing all the events again. */
  fun clearSearchAndFilters() = select(EventSelection())

  private fun selection() = _uiState.value.selection

  private fun select(selection: EventSelection) {
    savedState[KEY_SEARCH] = selection.search
    savedState[KEY_CATEGORIES] = ArrayList(selection.categories.map { it.name })
    savedState[KEY_DATE] = selection.date.name
    val list = loaded?.let { listFor(it, selection) } ?: _uiState.value.list
    _uiState.value = EventsUiState(list, selection)
  }

  private fun restoreSelection() =
      EventSelection(
          search = savedState.get<String>(KEY_SEARCH).orEmpty(),
          categories =
              savedState
                  .get<ArrayList<String>>(KEY_CATEGORIES)
                  .orEmpty()
                  .mapNotNull { name -> EventCategory.entries.firstOrNull { it.name == name } }
                  .toSet(),
          date =
              DateChoice.entries.firstOrNull { it.name == savedState.get<String>(KEY_DATE) }
                  ?: DateChoice.ANY,
      )

  private fun observeEvents() {
    observation?.cancel()
    loaded = null
    observation = viewModelScope.launch {
      repository.getUpcomingPublicEvents().collect { result ->
        val list =
            when (result) {
              PublicEventsResult.Error -> {
                loaded = null
                EventsListState.Error
              }
              is PublicEventsResult.Events -> {
                val events = Loaded(result.events, result.fromCache && !network.isOnline())
                loaded = events
                listFor(events, selection())
              }
            }
        _uiState.value = _uiState.value.copy(list = list)
      }
    }
  }

  private fun listFor(loaded: Loaded, selection: EventSelection): EventsListState {
    val isOffline = loaded.isOffline
    if (loaded.events.isEmpty()) return EventsListState.Empty(isOffline)
    val today = LocalDate.now(clock)
    val matching =
        filterEvents(loaded.events, selection.toFilter(today), clock.zone, userLocation = null)
    return if (matching.isEmpty()) EventsListState.NoMatch(isOffline)
    else EventsListState.Events(groupEventsByDay(matching, clock), today, isOffline)
  }

  /** Events from the repository, and whether they are an offline copy. */
  private class Loaded(val events: List<Event>, val isOffline: Boolean)

  private companion object {
    const val KEY_SEARCH = "events_search"
    const val KEY_CATEGORIES = "events_categories"
    const val KEY_DATE = "events_date"
  }
}
