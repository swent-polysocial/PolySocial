// Contributors: Claude (an association's own events on its Events tab, #132).
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
import kotlinx.coroutines.Job
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
 * splits them into upcoming and past ones each time they change. Like the students' Events tab,
 * `isOffline` needs both the cache and [network] saying offline, since Firestore's first snapshot
 * comes from the cache even online. After an error the repository stops emitting, so the state
 * stays [AssociationEventsState.Error] until [retry].
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

  init {
    observe()
  }

  /** Loads the events again after an error. Does nothing in any other state. */
  fun retry() {
    if (_state.value != AssociationEventsState.Error) return
    _state.value = AssociationEventsState.Loading
    observe()
  }

  private fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      repository.getOwnAssociationEvents().collect { result ->
        _state.value =
            when (result) {
              OwnEventsResult.Error -> AssociationEventsState.Error
              is OwnEventsResult.Events -> {
                val now = clock.instant()
                val split = splitUpcomingAndPast(result.events, now)
                AssociationEventsState.Events(
                    split.upcoming,
                    split.past,
                    now,
                    isOffline = result.fromCache && !network.isOnline(),
                )
              }
            }
      }
    }
  }
}
