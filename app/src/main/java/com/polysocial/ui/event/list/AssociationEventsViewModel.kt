// Contributors: Claude (an association's own events on its Events tab, #132; keeping the split
// current, #132 review).
package com.polysocial.ui.event.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.OwnEventsResult
import com.polysocial.model.event.splitUpcomingAndPast
import com.polysocial.model.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the association's Events tab shows. */
sealed interface AssociationEventsState {
  /** The first result has not arrived yet. */
  data object Loading : AssociationEventsState

  /**
   * The association's events, split at [now] into the Upcoming and Past tabs (see
   * `splitUpcomingAndPast`). [now] is when they were split, so the cards show "Live" for the same
   * instant. `isOffline` is true when they come from the cache while the device is offline.
   */
  data class Events(
      val upcoming: List<Event>,
      val past: List<Event>,
      val now: Instant,
      val isOffline: Boolean,
  ) : AssociationEventsState

  /** The events could not be loaded; the screen offers Retry. */
  data object Error : AssociationEventsState
}

/**
 * Observes the signed-in association's own events ([EventRepository.getOwnAssociationEvents]) and
 * splits them into upcoming and past ones each time they change, and again every [REFRESH_INTERVAL]
 * while [keepSplitCurrent] runs, so an event that has ended moves to Past and loses its "Live" chip
 * without new data. Like the students' Events tab, `isOffline` needs both the cache and [network]
 * saying offline, since Firestore's first snapshot comes from the cache even online. After an error
 * the repository stops emitting, so the state stays [AssociationEventsState.Error] until [retry].
 *
 * @param clock gives the current time for the split and the time zone of the cards.
 */
@HiltViewModel
class AssociationEventsViewModel
@Inject
constructor(
    private val repository: EventRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {
  private val _state = MutableStateFlow<AssociationEventsState>(AssociationEventsState.Loading)
  val state: StateFlow<AssociationEventsState> = _state.asStateFlow()

  /** The time zone the cards show dates and times in. */
  val zone: ZoneId
    get() = clock.zone

  /** The current date, so the cards can say "Today" and "Tomorrow". */
  val today: LocalDate
    get() = LocalDate.now(clock)

  private var observation: Job? = null

  /** The last events the repository sent, split again by [refresh]. */
  private var lastEvents: OwnEventsResult.Events? = null

  init {
    observe()
  }

  /** Loads the events again after an error. Does nothing in any other state. */
  fun retry() {
    if (_state.value != AssociationEventsState.Error) return
    _state.value = AssociationEventsState.Loading
    observe()
  }

  /** Splits the last events again at the current time. Does nothing before they arrive. */
  fun refresh() {
    if (_state.value is AssociationEventsState.Events) lastEvents?.let(::show)
  }

  /**
   * Refreshes the split now and then every [REFRESH_INTERVAL] until cancelled. The screen runs it
   * while it is shown, so it also catches up when the app comes back to the foreground.
   */
  suspend fun keepSplitCurrent() {
    while (true) {
      refresh()
      delay(REFRESH_INTERVAL)
    }
  }

  private fun show(result: OwnEventsResult.Events) {
    val now = clock.instant()
    val split = splitUpcomingAndPast(result.events, now)
    _state.value =
        AssociationEventsState.Events(
            split.upcoming,
            split.past,
            now,
            isOffline = result.fromCache && !network.isOnline(),
        )
  }

  private fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      repository.getOwnAssociationEvents().collect { result ->
        when (result) {
          OwnEventsResult.Error -> _state.value = AssociationEventsState.Error
          is OwnEventsResult.Events -> {
            lastEvents = result
            show(result)
          }
        }
      }
    }
  }

  companion object {
    /** How often [keepSplitCurrent] splits the events again: the cards show times to the minute. */
    val REFRESH_INTERVAL: Duration = 1.minutes
  }
}
