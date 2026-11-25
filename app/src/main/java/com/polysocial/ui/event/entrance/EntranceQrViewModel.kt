// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Entrance QR's states and rotation, #468);
// Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Opus 5.5, Anthropic) (rotation beside the expiry check and following the
// schedule, #538 review); Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Opus 5.5, Anthropic) (a bounded wait for the first code, #538 review);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.event.entrance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.checkin.CheckInRepository
import com.polysocial.model.checkin.CheckInWindow
import com.polysocial.model.checkin.ENTRANCE_CODE_ROTATION
import com.polysocial.model.checkin.EntranceCode
import com.polysocial.model.checkin.EntranceCodeStatus
import com.polysocial.model.checkin.RotateCodeResult
import com.polysocial.model.checkin.checkInWindow
import com.polysocial.model.checkin.isScannable
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.qr.QrCode
import com.polysocial.model.qr.checkInQrPayload
import com.polysocial.model.qr.encodeQrCode
import com.polysocial.ui.event.detail.EventDetailViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the Entrance QR screen shows (Figma section 20). */
sealed interface EntranceQrUiState {
  /** The event or its code is being read. */
  data object Loading : EntranceQrUiState

  /** Frame 06: the event's current entrance [code], drawn from the stored token. */
  data class Shown(val title: String, val venue: String, val code: QrCode) : EntranceQrUiState

  /**
   * Frame 16: offline (the code comes only from the cache) or the shown code is too old to scan: no
   * code is drawn, so the screen never shows one the rules would refuse.
   */
  data class Offline(val title: String) : EntranceQrUiState

  /** Frame 18: before the check-in window; [opensAt] is its opening time, as "19:30". */
  data class NotOpenYet(val title: String, val venue: String, val opensAt: String) :
      EntranceQrUiState

  /** Frame 19: the window has closed; [closedAt] is its closing time, as "01:00". */
  data class Closed(val title: String, val venue: String, val closedAt: String) : EntranceQrUiState

  /**
   * Frame 20: the event or its code couldn't be read, the rules refused the read (not an organizer)
   * or a rotation failed so long that the shown code expired. [title] is null when the event itself
   * couldn't be read.
   */
  data class Failed(val title: String?) : EntranceQrUiState
}

/**
 * The organizer's Entrance QR (#468, decided in #374): the event's stored entrance code, which
 * every organizer's phone draws, so they all show the same one, and which this phone rotates when
 * it is [ENTRANCE_CODE_ROTATION] old ([CheckInRepository.rotateEntranceCode] writes once even when
 * several phones try).
 *
 * The event is followed for its title, venue and [checkInWindow], so a schedule another organizer
 * changes while the screen is open applies at once; only a change to these starts the screen over.
 * Before the window opens, the screen says when it does, without reading or writing the code (the
 * rules would refuse it), and starts once it opens; once the window has closed, it says so. During
 * the window it follows [CheckInRepository.observeEntranceCode]: a missing code is created (waiting
 * at most [FIRST_CODE_TIMEOUT], and never past the window's close), a code from the cache is hidden
 * (offline), and a stored code is shown while [isScannable] says a student scanning it would still
 * be checked in, re-checked every [TICK], also while its rotation is still waiting for an answer;
 * once it isn't (its rotation kept failing or hangs), the screen says it couldn't load. A rotation
 * is tried again after [ROTATION_RETRY] until the listener brings the new code. [retry] starts
 * over, for Try again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EntranceQrViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val events: EventRepository,
    private val checkIns: CheckInRepository,
    private val clock: Clock,
) : ViewModel() {
  private val eventId = savedStateHandle.get<String>(EventDetailViewModel.EVENT_ID_ARG).orEmpty()
  private val attempts = MutableStateFlow(0)
  private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(clock.zone)

  val uiState: StateFlow<EntranceQrUiState> =
      attempts
          .flatMapLatest { entranceQr() }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
              EntranceQrUiState.Loading,
          )

  /** Try again: reads the event and its code again. */
  fun retry() {
    attempts.value++
  }

  private fun entranceQr(): Flow<EntranceQrUiState> = flow {
    emit(EntranceQrUiState.Loading)
    emitAll(
        events
            .getEvent(eventId)
            .map { result ->
              when (result) {
                is EventResult.Found ->
                    Schedule(
                        result.event.title,
                        result.event.locationName,
                        checkInWindow(result.event),
                    )
                EventResult.NotFound,
                EventResult.NotCached -> null
              }
            }
            .distinctUntilChanged()
            .transformLatest { schedule ->
              if (schedule == null) emit(EntranceQrUiState.Failed(null)) else during(schedule)
            }
    )
  }

  /**
   * What the screen uses of the event: its other changes (such as the attendee count) don't start
   * the screen over, which would listen to the code again from the cache.
   */
  private data class Schedule(val title: String, val venue: String, val window: CheckInWindow)

  /** The screen for [event]'s schedule, from before its window opens until it closes. */
  private suspend fun FlowCollector<EntranceQrUiState>.during(event: Schedule) {
    val window = event.window
    while (clock.instant().isBefore(window.opensAt)) {
      emit(EntranceQrUiState.NotOpenYet(event.title, event.venue, time.format(window.opensAt)))
      delay(Duration.between(clock.instant(), window.opensAt).toMillis().coerceAtLeast(1))
    }
    if (closed(event, window)) return
    emitAll(
        checkIns.observeEntranceCode(eventId).transformLatest { status ->
          if (closed(event, window)) return@transformLatest
          when (status) {
            EntranceCodeStatus.Error -> emit(EntranceQrUiState.Failed(event.title))
            EntranceCodeStatus.Missing -> {
              emit(EntranceQrUiState.Loading)
              createFirstCode(event, window)
            }
            is EntranceCodeStatus.Code ->
                if (status.fromCache) emit(EntranceQrUiState.Offline(event.title))
                else show(status.code, event, window)
          }
        }
    )
  }

  /** Emits [EntranceQrUiState.Closed] and returns true once [window] has closed. */
  private suspend fun FlowCollector<EntranceQrUiState>.closed(
      event: Schedule,
      window: CheckInWindow,
  ): Boolean {
    if (clock.instant().isBefore(window.closesAt)) return false
    emit(EntranceQrUiState.Closed(event.title, event.venue, time.format(window.closesAt)))
    return true
  }

  /**
   * Creates the event's first code; the listener then shows it. A creation still unanswered after
   * [FIRST_CODE_TIMEOUT] fails (Try again), and one still unanswered when [window] closes gives
   * [EntranceQrUiState.Closed], so the screen never stays loading.
   */
  private suspend fun FlowCollector<EntranceQrUiState>.createFirstCode(
      event: Schedule,
      window: CheckInWindow,
  ) {
    val untilClosed = Duration.between(clock.instant(), window.closesAt)
    val wait = minOf(FIRST_CODE_TIMEOUT, untilClosed).toMillis().coerceAtLeast(1)
    when (withTimeoutOrNull(wait) { checkIns.rotateEntranceCode(eventId, null) }) {
      null -> if (!closed(event, window)) emit(EntranceQrUiState.Failed(event.title))
      RotateCodeResult.Done -> Unit
      RotateCodeResult.NetworkError -> emit(EntranceQrUiState.Offline(event.title))
      RotateCodeResult.UnexpectedError -> emit(EntranceQrUiState.Failed(event.title))
    }
  }

  /**
   * Shows [code] while it is scannable, every [TICK], and rotates it once it is due. The rotation
   * runs beside the check, so a code whose rotation hangs still disappears once it is too old. Runs
   * until the listener delivers another status (the new code), which cancels it, or the window
   * closes.
   */
  private suspend fun FlowCollector<EntranceQrUiState>.show(
      code: EntranceCode,
      event: Schedule,
      window: CheckInWindow,
  ) {
    val qr = checkInQrPayload(eventId, code.token)?.let(::encodeQrCode)
    if (qr == null) {
      emit(EntranceQrUiState.Failed(event.title))
      return
    }
    var rotateAt = code.rotatedAt + ENTRANCE_CODE_ROTATION
    var rotation: Job? = null
    coroutineScope {
      while (!closed(event, window)) {
        val now = clock.instant()
        emit(
            if (code.isScannable(now)) EntranceQrUiState.Shown(event.title, event.venue, qr)
            else EntranceQrUiState.Failed(event.title)
        )
        if (rotation?.isActive != true && !now.isBefore(rotateAt)) {
          // Done or not, the listener brings any new code, which cancels this loop. Until then, a
          // failed rotation (offline, refused) is tried again later, never every tick.
          rotation = launch {
            checkIns.rotateEntranceCode(eventId, code.token)
            rotateAt = clock.instant() + ROTATION_RETRY
          }
        }
        delay(TICK.toMillis())
      }
      rotation?.cancel()
    }
  }

  private companion object {
    /** How often the shown code's age is checked again. */
    val TICK: Duration = Duration.ofSeconds(1)

    /** How long the first code's creation may wait for an answer before it fails. */
    val FIRST_CODE_TIMEOUT: Duration = Duration.ofSeconds(10)

    /** How long after a failed rotation this phone tries again. */
    val ROTATION_RETRY: Duration = Duration.ofSeconds(10)

    const val STOP_TIMEOUT_MS = 5_000L
  }
}
