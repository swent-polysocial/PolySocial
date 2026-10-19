// Contributors: Claude (drafted the event detail ViewModel, #112; the server timeout and Retry,
// #194).
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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

  /**
   * The event was never loaded on the device, and the device is offline or the server didn't answer
   * within [EventDetailViewModel.SERVER_ANSWER_TIMEOUT_MILLIS]. It loads once the server answers,
   * or the user taps Retry.
   */
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
 * Android can report a working network while Firestore can't reach its server (a firewall blocking
 * Firestore, a server outage, a connection that died silently). Firestore then withholds its cached
 * "not saved" answer until it decides by itself that it is offline, or answers nothing at all. So
 * each read loads for at most [SERVER_ANSWER_TIMEOUT_MILLIS] from its start while online, then
 * shows [EventDetailUiState.OfflineNotSaved] with Retry. The read keeps listening after the
 * timeout, so a late answer from the server still shows the event.
 *
 * @param clock gives the device's time zone, in which the times are shown.
 */
@HiltViewModel
class EventDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: EventRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {

  private val eventId = savedStateHandle.get<String>(EVENT_ID_ARG).orEmpty()

  /** Bumped by [retry], so the event is read again. */
  private val reads = MutableStateFlow(0)

  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<EventDetailUiState> =
      reads
          .flatMapLatest { eventStates().onStart { emit(EventDetailUiState.Loading) } }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
              EventDetailUiState.Loading,
          )

  /**
   * Reads the event again, showing [EventDetailUiState.Loading] meanwhile (the offline "not saved"
   * state's Retry). It replaces the previous read, and the new read has its own
   * [SERVER_ANSWER_TIMEOUT_MILLIS].
   */
  fun retry() {
    reads.update { it + 1 }
  }

  /**
   * One read of the event, live. Its timeout starts with the read, not with the repository's first
   * answer, which may never come; later answers don't restart it.
   */
  private fun eventStates(): Flow<EventDetailUiState> =
      combine(
          // null until the repository answers.
          repository.getEvent(eventId).map<EventResult, EventResult?> { it }.onStart { emit(null) },
          flow {
            emit(false)
            delay(SERVER_ANSWER_TIMEOUT_MILLIS)
            emit(true)
          },
      ) { result, timedOut ->
        stateOf(result, timedOut)
      }

  /**
   * What the screen shows for the repository's latest [result] (null before its first answer),
   * [timedOut] telling whether the read's [SERVER_ANSWER_TIMEOUT_MILLIS] has passed. Before any
   * answer it loads, even offline, until the timeout; a cached "not saved" shows at once offline.
   * The timer firing after an answer recomputes the same state, which the [StateFlow] drops.
   */
  private fun stateOf(result: EventResult?, timedOut: Boolean): EventDetailUiState =
      when (result) {
        is EventResult.Found ->
            EventDetailUiState.Loaded(
                eventDetails(result.event, clock.zone),
                isOffline = result.fromCache && !network.isOnline(),
            )
        EventResult.NotFound -> EventDetailUiState.NotFound
        // Even offline, the cache answers a moment later and may have the event.
        null -> if (timedOut) EventDetailUiState.OfflineNotSaved else EventDetailUiState.Loading
        // Online, the server may still answer.
        EventResult.NotCached ->
            if (timedOut || !network.isOnline()) EventDetailUiState.OfflineNotSaved
            else EventDetailUiState.Loading
      }

  companion object {
    /** Name of the navigation argument holding the event's ID. */
    const val EVENT_ID_ARG = "eventId"

    /** Keeps listening this long after the screen stops, so a rotation doesn't restart the read. */
    private const val STOP_TIMEOUT_MILLIS = 5_000L

    /**
     * How long a read waits for the server while online, from its start, before the screen gives up
     * and shows the offline "not saved" state with Retry. Firestore only reports a missing,
     * never-read event once it has decided by itself that it is offline (or never, if the
     * connection died silently), so without this the screen could load forever. Ten seconds is long
     * enough for a slow but working connection, and the same bound as the session cache cleanup.
     */
    const val SERVER_ANSWER_TIMEOUT_MILLIS = 10_000L
  }
}
