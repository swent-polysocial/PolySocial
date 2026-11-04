// Contributors: Claude Opus 5.5 (medium) (My events from the student's registrations, #204).
package com.polysocial.ui.event.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Event
import com.polysocial.model.event.splitUpcomingAndPast
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.ChosenEventsRepository
import com.polysocial.model.registration.ChosenEventsResult
import com.polysocial.model.registration.RegistrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The My events list (Figma section 12). */
sealed interface MyEventsList {
  /** The chosen events haven't arrived yet (at most [MyEventsViewModel.LOAD_TIMEOUT]). */
  data object Loading : MyEventsList

  /** The student hasn't chosen any event ("Nothing planned yet"). */
  data object Empty : MyEventsList

  /**
   * The chosen events, split at [now] like the association's tab (`splitUpcomingAndPast`): upcoming
   * ones (live ones included) soonest first, past ones latest first.
   *
   * @property unavailableIds chosen events that were deleted or can no longer be read, shown
   *   without any detail; the student can remove them.
   * @property missingCount chosen events that can't be shown yet: not saved on this device while
   *   offline, or still unanswered after [MyEventsViewModel.LOAD_TIMEOUT].
   * @property isOffline true when the list comes from the cache and the device is offline.
   */
  data class Events(
      val upcoming: List<Event>,
      val past: List<Event>,
      val unavailableIds: List<String>,
      val missingCount: Int,
      val now: Instant,
      val isOffline: Boolean,
  ) : MyEventsList

  /** Offline with no chosen event saved on this device; Retry reads again. */
  data object OfflineNothingSaved : MyEventsList

  /** The chosen events couldn't be read; Retry reads again. */
  data object Error : MyEventsList
}

/** A message the screen shows once in a Snackbar, after removing an unavailable event failed. */
enum class MyEventsMessage {
  /** The device is offline, so the registration was not removed. */
  REMOVE_OFFLINE,

  /** Any other failure. */
  REMOVE_FAILED,
}

/**
 * What My events shows: the [list], the unavailable events being removed, and a pending [message].
 */
data class MyEventsUiState(
    val list: MyEventsList = MyEventsList.Loading,
    val removing: Set<String> = emptySet(),
    val message: MyEventsMessage? = null,
) {
  /** The count on the "My events · N" segment: the upcoming chosen events, or null until known. */
  val upcomingCount: Int?
    get() =
        when (list) {
          is MyEventsList.Events -> list.upcoming.size
          MyEventsList.Empty -> 0
          else -> null
        }
}

/**
 * Observes the signed-in student's chosen events ([ChosenEventsRepository]) for My events (#204).
 *
 * The list updates by itself when the student registers or cancels, and when a chosen event is
 * edited or deleted. It is split again every [REFRESH_INTERVAL] while [keepSplitCurrent] runs, so
 * an event that has ended moves to Past. Like the Events tab, `isOffline` needs both the cache and
 * [network] saying offline, since Firestore's first snapshot comes from the cache even online.
 *
 * It never loads forever: Firestore can stay silent about a document it never read (see CONTEXT.md,
 * Traps), so after [LOAD_TIMEOUT] the events still unanswered count as missing, and if nothing at
 * all could be shown the list becomes [MyEventsList.OfflineNothingSaved] or [MyEventsList.Error],
 * both with Retry. A late answer still shows.
 *
 * An unavailable event shows no detail; [remove] deletes the student's own registration for it,
 * which works even once the event is deleted.
 *
 * Nothing here belongs to another account: the student is read when the observation starts, and
 * logout clears the tab's back stack, and with it this ViewModel.
 *
 * @param clock gives the current time for the split and the time zone of the cards.
 */
@HiltViewModel
class MyEventsViewModel
@Inject
constructor(
    private val chosenEvents: ChosenEventsRepository,
    private val registrations: RegistrationRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {
  private val _state = MutableStateFlow(MyEventsUiState())
  val state: StateFlow<MyEventsUiState> = _state.asStateFlow()

  /** The time zone the cards show dates and times in. */
  val zone: ZoneId
    get() = clock.zone

  /** The current date, so the cards can say "Today" and "Tomorrow". */
  val today: LocalDate
    get() = LocalDate.now(clock)

  private var observation: Job? = null
  private var timeout: Job? = null
  private var last: ChosenEventsResult.Events? = null
  private var timedOut = false

  init {
    observe()
  }

  /** Reads the chosen events again. Does nothing unless the list is an error or offline state. */
  fun retry() {
    val list = _state.value.list
    if (list != MyEventsList.Error && list != MyEventsList.OfflineNothingSaved) return
    _state.update { it.copy(list = MyEventsList.Loading) }
    observe()
  }

  /** Splits the last chosen events again at the current time. Does nothing before they arrive. */
  fun refresh() {
    if (_state.value.list is MyEventsList.Events) last?.let(::show)
  }

  /** Refreshes the split now and then every [REFRESH_INTERVAL] until cancelled. */
  suspend fun keepSplitCurrent() {
    while (true) {
      refresh()
      delay(REFRESH_INTERVAL)
    }
  }

  /**
   * Removes the student's registration for the unavailable event [eventId]. The list drops it by
   * itself once the registration is gone; a failure shows a [MyEventsMessage]. A second tap while
   * it is being removed does nothing.
   */
  fun remove(eventId: String) {
    if (eventId in _state.value.removing) return
    _state.update { it.copy(removing = it.removing + eventId) }
    viewModelScope.launch {
      val message =
          when (registrations.cancel(eventId)) {
            CancelRegistrationResult.Cancelled -> null
            CancelRegistrationResult.NetworkError -> MyEventsMessage.REMOVE_OFFLINE
            CancelRegistrationResult.NotSignedIn,
            CancelRegistrationResult.UnexpectedError -> MyEventsMessage.REMOVE_FAILED
          }
      _state.update { it.copy(removing = it.removing - eventId, message = message ?: it.message) }
    }
  }

  /** The screen showed [MyEventsUiState.message]. */
  fun messageShown() {
    _state.update { it.copy(message = null) }
  }

  private fun observe() {
    observation?.cancel()
    timeout?.cancel()
    last = null
    timedOut = false
    timeout = viewModelScope.launch {
      delay(LOAD_TIMEOUT)
      timedOut = true
      val result = last
      if (result != null) show(result)
      else if (_state.value.list == MyEventsList.Loading) setList(nothingShown())
    }
    observation = viewModelScope.launch {
      chosenEvents.observeChosenEvents().collect { result ->
        when (result) {
          ChosenEventsResult.Error -> {
            timeout?.cancel()
            setList(MyEventsList.Error)
          }
          is ChosenEventsResult.Events -> {
            last = result
            show(result)
          }
        }
      }
    }
  }

  private fun show(result: ChosenEventsResult.Events) {
    val isOffline = result.fromCache && !network.isOnline()
    val nothingRegistered =
        result.events.isEmpty() &&
            result.unavailableIds.isEmpty() &&
            result.notCachedIds.isEmpty() &&
            result.pendingIds.isEmpty()
    val nothingToShow = result.events.isEmpty() && result.unavailableIds.isEmpty()
    setList(
        when {
          // Online, the cache's first answer can be empty until the server's arrives.
          nothingRegistered && result.fromCache && !isOffline && !timedOut -> MyEventsList.Loading
          nothingRegistered ->
              if (isOffline) MyEventsList.OfflineNothingSaved else MyEventsList.Empty
          nothingToShow && result.notCachedIds.isEmpty() && !timedOut -> MyEventsList.Loading
          nothingToShow -> if (isOffline) MyEventsList.OfflineNothingSaved else nothingShown()
          else -> {
            val now = clock.instant()
            val split = splitUpcomingAndPast(result.events, now)
            MyEventsList.Events(
                upcoming = split.upcoming,
                past = split.past,
                unavailableIds = result.unavailableIds.sorted(),
                missingCount =
                    result.notCachedIds.size + if (timedOut) result.pendingIds.size else 0,
                now = now,
                isOffline = isOffline,
            )
          }
        }
    )
  }

  /** The list when no chosen event could be shown at all. */
  private fun nothingShown(): MyEventsList =
      if (network.isOnline()) MyEventsList.Error else MyEventsList.OfflineNothingSaved

  private fun setList(list: MyEventsList) {
    _state.update { it.copy(list = list) }
  }

  companion object {
    /** How often [keepSplitCurrent] splits the events again: the cards show times to the minute. */
    val REFRESH_INTERVAL: Duration = 1.minutes

    /**
     * How long the list waits for answers before showing what it has, like the event detail's
     * `SERVER_ANSWER_TIMEOUT_MILLIS`.
     */
    val LOAD_TIMEOUT: Duration = 10.seconds
  }
}
