// Contributors: Claude (drafted the event detail ViewModel, #112; the server timeout and Retry,
// #194; Edit and Cancel event for organizers, #131; the association's name, #128).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationNames
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.DeleteEventResult
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
   * @property isOrganizer true when the signed-in user organizes the event, so the screen offers
   *   Edit and Cancel event (#131).
   * @property associationName the name of the association that publishes the event, once read
   *   (#128); null for a student's event, and while or when it can't be read.
   */
  data class Loaded(
      val details: EventDetails,
      val isOffline: Boolean,
      val isOrganizer: Boolean = false,
      val associationName: String? = null,
  ) : EventDetailUiState

  /** The event doesn't exist (anymore), or the user isn't allowed to see it. */
  data object NotFound : EventDetailUiState

  /**
   * The event was never loaded on the device, and the device is offline or the server didn't answer
   * within [EventDetailViewModel.SERVER_ANSWER_TIMEOUT_MILLIS]. It loads once the server answers,
   * or the user taps Retry.
   */
  data object OfflineNotSaved : EventDetailUiState
}

/** Where "Cancel event" is (Figma section 10, "Cancel event · …"). */
sealed interface CancelEventState {
  /** The confirmation isn't shown. */
  data object Hidden : CancelEventState

  /** The confirmation asks whether to cancel the event. */
  data object Confirming : CancelEventState

  /** The event is being deleted; the confirmation can't be dismissed meanwhile. */
  data object Cancelling : CancelEventState

  /** The event wasn't cancelled; the confirmation stays with Try again. */
  data class Failed(val reason: Reason) : CancelEventState {
    enum class Reason {
      OFFLINE,
      UNEXPECTED,
    }
  }

  /** The event was deleted: the screen closes. */
  data object Cancelled : CancelEventState
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
 * Its organizers can also cancel it ([cancelState]): a confirmation, then
 * [EventRepository.deleteEvent] (in Sprint 3, cancelling deletes the event). A failed or refused
 * delete is never reported as cancelled: the confirmation stays with Try again. While it is
 * deleted, the event's live read reports it missing, so the screen keeps showing the event until it
 * closes.
 *
 * An association's event shows the association's name (#128), read once through the shared
 * [associationNames]; the event shows at once, and the name joins it when it arrives.
 *
 * @param auth tells whether the signed-in user is one of the event's organizers.
 * @param clock gives the device's time zone, in which the times are shown.
 */
@HiltViewModel
class EventDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: EventRepository,
    private val network: NetworkMonitor,
    private val auth: AuthRepository,
    private val clock: Clock,
    private val associationNames: AssociationNames,
) : ViewModel() {

  private val eventId = savedStateHandle.get<String>(EVENT_ID_ARG).orEmpty()

  /** Bumped by [retry], so the event is read again. */
  private val reads = MutableStateFlow(0)

  private val _cancelState = MutableStateFlow<CancelEventState>(CancelEventState.Hidden)
  val cancelState: StateFlow<CancelEventState> = _cancelState.asStateFlow()

  /** The event last shown, kept on screen while it is deleted. */
  private var lastLoaded: EventDetailUiState.Loaded? = null

  /** The association whose name this screen asked for, so it is asked for once. */
  private var requestedName: String? = null

  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<EventDetailUiState> =
      reads
          .flatMapLatest { eventStates().onStart { emit(EventDetailUiState.Loading) } }
          .onEach(::loadAssociationName)
          .combine(associationNames.names, ::withAssociationName)
          .combine(_cancelState) { state, cancel ->
            val deleting =
                cancel == CancelEventState.Cancelling || cancel == CancelEventState.Cancelled
            if (state is EventDetailUiState.Loaded) lastLoaded = state
            if (deleting && state !is EventDetailUiState.Loaded) lastLoaded ?: state else state
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
              EventDetailUiState.Loading,
          )

  /** Asks once for the name of the association that publishes the event in [state], if any. */
  private fun loadAssociationName(state: EventDetailUiState) {
    val event = (state as? EventDetailUiState.Loaded)?.details?.event ?: return
    if (!event.isAssociationEvent || requestedName == event.createdBy) return
    requestedName = event.createdBy
    viewModelScope.launch { associationNames.load(event.createdBy) }
  }

  /** [state] with its association's name from [names], when it is an association's event. */
  private fun withAssociationName(
      state: EventDetailUiState,
      names: Map<String, String>,
  ): EventDetailUiState {
    val event = (state as? EventDetailUiState.Loaded)?.details?.event ?: return state
    return if (event.isAssociationEvent) state.copy(associationName = names[event.createdBy])
    else state
  }

  /**
   * Reads the event again, showing [EventDetailUiState.Loading] meanwhile (the offline "not saved"
   * state's Retry). It replaces the previous read, and the new read has its own
   * [SERVER_ANSWER_TIMEOUT_MILLIS].
   */
  fun retry() {
    reads.update { it + 1 }
  }

  /** Asks to confirm "Cancel event". */
  fun onCancelEvent() {
    _cancelState.compareAndSet(CancelEventState.Hidden, CancelEventState.Confirming)
  }

  /** "Keep event", or dismissing the confirmation: nothing changes. Ignored while cancelling. */
  fun onKeepEvent() {
    _cancelState.update {
      if (it == CancelEventState.Cancelling || it == CancelEventState.Cancelled) it
      else CancelEventState.Hidden
    }
  }

  /** Deletes the event, from the confirmation or its Try again. */
  fun onConfirmCancel() {
    val state = _cancelState.value
    if (state != CancelEventState.Confirming && state !is CancelEventState.Failed) return
    _cancelState.value = CancelEventState.Cancelling
    viewModelScope.launch {
      _cancelState.value =
          when (repository.deleteEvent(eventId)) {
            DeleteEventResult.Deleted -> CancelEventState.Cancelled
            DeleteEventResult.NetworkError ->
                CancelEventState.Failed(CancelEventState.Failed.Reason.OFFLINE)
            // Never reported as cancelled. A missing event shows "not found" behind (#131).
            DeleteEventResult.NotOrganizer,
            DeleteEventResult.NotFound,
            DeleteEventResult.UnexpectedError ->
                CancelEventState.Failed(CancelEventState.Failed.Reason.UNEXPECTED)
          }
    }
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
                isOrganizer = auth.currentUser()?.uid in result.event.organizerIds,
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
