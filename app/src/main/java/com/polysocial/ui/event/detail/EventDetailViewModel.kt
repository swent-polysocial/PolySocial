// Contributors: Claude (drafted the event detail ViewModel, #112).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * An event with its times in the device's time zone, as the detail screen shows them.
 *
 * @property endTime the end time, or null if the organizer didn't give one.
 * @property endDayOffset how many days after [date] the event ends: n > 0 shows "+n day(s)" next to
 *   the end time ("+1 day" for an overnight event), 0 means it ends the same day or has no end
 *   time.
 */
data class EventDetails(
    val event: Event,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime?,
    val endDayOffset: Int,
)

/** Returns [event] with its times read in [zone]. */
fun eventDetails(event: Event, zone: ZoneId): EventDetails {
  val start = event.startTime.atZone(zone)
  val end = event.endTime?.atZone(zone)
  val offset = end?.let { ChronoUnit.DAYS.between(start.toLocalDate(), it.toLocalDate()) } ?: 0
  return EventDetails(
      event = event,
      date = start.toLocalDate(),
      startTime = start.toLocalTime(),
      endTime = end?.toLocalTime(),
      endDayOffset = offset.toInt(),
  )
}

/** What the event detail screen shows (Figma section 03, "Event detail" and its states). */
sealed interface EventDetailUiState {
  data object Loading : EventDetailUiState

  /**
   * The event.
   *
   * @property isOffline true when the device is offline and the event comes from the offline cache,
   *   so the screen says it shows the details saved on the phone.
   */
  data class Loaded(val details: EventDetails, val isOffline: Boolean) : EventDetailUiState

  /** The event doesn't exist (anymore), or the user isn't allowed to see it. */
  data object NotFound : EventDetailUiState

  /** The device is offline and the event was never loaded on it. It loads once back online. */
  data object OfflineNotSaved : EventDetailUiState
}

/**
 * Shows the event whose ID is the navigation argument [EVENT_ID_ARG], live: the screen updates when
 * the event changes, shows "not found" once it is deleted, and loads it by itself when the device
 * comes back online.
 *
 * Firestore's first snapshot often comes from the offline cache even while online (the map and the
 * Events tab cache every public event), and the server's answer follows a moment later. So "from
 * the cache" only counts as offline when [network] says the device is offline too; otherwise the
 * screen shows the cached event normally, or keeps loading while the server answers.
 *
 * @param clock gives the device's time zone, in which the times are shown.
 */
@HiltViewModel
class EventDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    repository: EventRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {

  val uiState: StateFlow<EventDetailUiState> =
      repository
          .getEvent(savedStateHandle.get<String>(EVENT_ID_ARG).orEmpty())
          .map { it.toUiState() }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
              EventDetailUiState.Loading,
          )

  private fun EventResult.toUiState(): EventDetailUiState =
      when (this) {
        is EventResult.Found ->
            EventDetailUiState.Loaded(
                eventDetails(event, clock.zone),
                isOffline = fromCache && !network.isOnline(),
            )
        EventResult.NotFound -> EventDetailUiState.NotFound
        EventResult.NotCached ->
            if (network.isOnline()) EventDetailUiState.Loading
            else EventDetailUiState.OfflineNotSaved
      }

  companion object {
    /** Name of the navigation argument holding the event's ID. */
    const val EVENT_ID_ARG = "eventId"

    /** Keeps listening this long after the screen stops, so a rotation doesn't restart the read. */
    private const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
