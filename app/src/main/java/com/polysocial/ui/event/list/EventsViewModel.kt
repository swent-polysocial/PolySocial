// Contributors: Claude (state of the Events tab's list, #105).
package com.polysocial.ui.event.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.PublicEventsResult
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

/** What the Events tab's list shows. */
sealed interface EventsUiState {
  /** The first result has not arrived yet. */
  data object Loading : EventsUiState

  /** There are no upcoming public events. */
  data class Empty(val isOffline: Boolean) : EventsUiState

  /**
   * The upcoming public events, grouped by day. [today] is the current date, so the screen can name
   * today's and tomorrow's sections.
   */
  data class Events(
      val sections: List<EventDaySection>,
      val today: LocalDate,
      val isOffline: Boolean,
  ) : EventsUiState

  /** The events could not be loaded; the screen offers Retry. */
  data object Error : EventsUiState
}

/**
 * Observes the upcoming public events from [EventRepository] and groups them by day for the Events
 * tab. The list updates whenever the events change. `isOffline` is true when the events come from
 * the offline cache, so the screen can say they may be out of date.
 *
 * After an error the repository stops emitting, so the list stays in [EventsUiState.Error] until
 * [retry].
 *
 * @param clock gives the current date and time zone for the day sections, so tests can fix them.
 */
@HiltViewModel
class EventsViewModel
@Inject
constructor(
    private val repository: EventRepository,
    private val clock: Clock,
) : ViewModel() {
  private val _uiState = MutableStateFlow<EventsUiState>(EventsUiState.Loading)
  val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

  /** The time zone of the day headings, in which the screen also shows the start times. */
  val zone: ZoneId
    get() = clock.zone

  private var observation: Job? = null

  init {
    observeEvents()
  }

  /** Loads the events again after an error. Does nothing in any other state. */
  fun retry() {
    if (_uiState.value != EventsUiState.Error) return
    _uiState.value = EventsUiState.Loading
    observeEvents()
  }

  private fun observeEvents() {
    observation?.cancel()
    observation = viewModelScope.launch {
      repository.getUpcomingPublicEvents().collect { result ->
        _uiState.value =
            when (result) {
              PublicEventsResult.Error -> EventsUiState.Error
              is PublicEventsResult.Events ->
                  if (result.events.isEmpty()) EventsUiState.Empty(result.fromCache)
                  else
                      EventsUiState.Events(
                          groupEventsByDay(result.events, clock),
                          LocalDate.now(clock),
                          result.fromCache,
                      )
            }
      }
    }
  }
}
