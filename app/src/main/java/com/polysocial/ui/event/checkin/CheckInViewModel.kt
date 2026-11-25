// Contributors: OpenAI Codex (GPT-6.2 Sol, high; check-in flow and session-ended state for #469
// under Lucas Pinto Ricardo’s
// supervision and approval).
package com.polysocial.ui.event.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.checkin.CheckInRepository
import com.polysocial.model.checkin.CheckInResult
import com.polysocial.model.checkin.CheckInStatus
import com.polysocial.model.checkin.CheckInWindow
import com.polysocial.model.checkin.checkInWindow
import com.polysocial.model.checkin.isAtVenue
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.location.LocationService
import com.polysocial.model.location.PreciseLocationResult
import com.polysocial.model.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Inputs that affect a pending check-in, independent of unrelated edits to the event. */
internal data class CheckInTarget(
    val eventId: String,
    val uid: String,
    val venue: Coordinates,
    val window: CheckInWindow,
) {
  constructor(event: Event, uid: String) : this(event.id, uid, event.location, checkInWindow(event))
}

internal sealed interface CheckInState {
  enum class Stage : CheckInState {
    LOADING,
    READY,
    AWAITING_PERMISSION,
    CHECKING_LOCATION,
    SAVING,
    DENIED,
    APPROXIMATE_ONLY,
    TOO_FAR,
    NO_FIX,
    TIMED_OUT,
    OFFLINE,
    STATUS_ERROR,
    SAVE_ERROR,
    WINDOW_CHANGED,
    REGISTRATION_REQUIRED,
    EVENT_UNAVAILABLE,
    SESSION_ENDED,
  }

  data class Confirmed(val time: Instant) : CheckInState
}

/** Coordinates one foreground venue check and shows the time returned by the repository. */
@HiltViewModel
internal class CheckInViewModel
@Inject
constructor(
    private val location: LocationService,
    private val repository: CheckInRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {
  private val mutableState = MutableStateFlow<CheckInState>(CheckInState.Stage.LOADING)
  val state = mutableState.asStateFlow()

  private var target: CheckInTarget? = null
  private var operation: Job? = null

  fun open(target: CheckInTarget) {
    if (this.target == target) return
    close()
    this.target = target
    readStatus(target)
  }

  fun close() {
    operation?.cancel()
    target = null
    mutableState.value = CheckInState.Stage.LOADING
  }

  /** Cancels a pending fix when the app leaves the foreground. */
  fun onStop() {
    if (state.value != CheckInState.Stage.CHECKING_LOCATION) return
    operation?.cancel()
    mutableState.value = CheckInState.Stage.READY
  }

  /** Returns true when the UI should launch Android's location permission prompt. */
  fun onLocationAction(preciseGranted: Boolean): Boolean {
    val target = target ?: return false
    when (state.value) {
      CheckInState.Stage.STATUS_ERROR -> {
        readStatus(target)
        return false
      }
      CheckInState.Stage.READY,
      CheckInState.Stage.DENIED,
      CheckInState.Stage.APPROXIMATE_ONLY,
      CheckInState.Stage.TOO_FAR,
      CheckInState.Stage.NO_FIX,
      CheckInState.Stage.TIMED_OUT,
      CheckInState.Stage.OFFLINE,
      CheckInState.Stage.SAVE_ERROR,
      CheckInState.Stage.WINDOW_CHANGED -> Unit
      else -> return false
    }
    if (!canStart(target)) return false
    if (!preciseGranted) {
      mutableState.value = CheckInState.Stage.AWAITING_PERMISSION
      return true
    }
    checkLocation(target)
    return false
  }

  fun onPermissionResult(precise: Boolean, approximate: Boolean) {
    val target = target ?: return
    if (state.value != CheckInState.Stage.AWAITING_PERMISSION || !canStart(target)) return
    when {
      precise -> checkLocation(target)
      approximate -> mutableState.value = CheckInState.Stage.APPROXIMATE_ONLY
      else -> mutableState.value = CheckInState.Stage.DENIED
    }
  }

  private fun canStart(target: CheckInTarget): Boolean {
    if (clock.instant() !in target.window) {
      mutableState.value = CheckInState.Stage.READY
      return false
    }
    if (!network.isOnline()) {
      mutableState.value = CheckInState.Stage.OFFLINE
      return false
    }
    return true
  }

  private fun readStatus(target: CheckInTarget) {
    mutableState.value = CheckInState.Stage.LOADING
    operation = viewModelScope.launch { mutableState.value = statusOf(target, afterWrite = false) }
  }

  private fun checkLocation(target: CheckInTarget) {
    mutableState.value = CheckInState.Stage.CHECKING_LOCATION
    operation = viewModelScope.launch {
      mutableState.value =
          when (val fix = location.currentPreciseLocation()) {
            PreciseLocationResult.PermissionDenied -> CheckInState.Stage.DENIED
            PreciseLocationResult.Unavailable -> CheckInState.Stage.NO_FIX
            PreciseLocationResult.TimedOut -> CheckInState.Stage.TIMED_OUT
            is PreciseLocationResult.Available ->
                if (isAtVenue(fix.coordinates, fix.accuracyMeters, target.venue)) {
                  saveCheckIn(target)
                } else {
                  CheckInState.Stage.TOO_FAR
                }
          }
    }
  }

  private suspend fun saveCheckIn(target: CheckInTarget): CheckInState {
    if (!canStart(target)) return state.value
    mutableState.value = CheckInState.Stage.SAVING
    return when (repository.checkIn(target.eventId)) {
      // The observer starts only after the transaction returns success, so it cannot show an
      // optimistic timestamp while the server is still deciding.
      CheckInResult.CheckedIn -> statusOf(target, afterWrite = true)
      CheckInResult.OutsideWindow -> CheckInState.Stage.WINDOW_CHANGED
      CheckInResult.NetworkError -> CheckInState.Stage.OFFLINE
      CheckInResult.NotRegistered -> CheckInState.Stage.REGISTRATION_REQUIRED
      CheckInResult.NotFound -> CheckInState.Stage.EVENT_UNAVAILABLE
      CheckInResult.NotSignedIn -> CheckInState.Stage.SESSION_ENDED
      CheckInResult.UnexpectedError -> CheckInState.Stage.SAVE_ERROR
    }
  }

  private suspend fun statusOf(target: CheckInTarget, afterWrite: Boolean): CheckInState {
    val status =
        withTimeoutOrNull(STATUS_TIMEOUT_MILLIS) {
          val updates = repository.observeCheckIn(target.eventId, target.uid)
          if (afterWrite) updates.firstOrNull { it != CheckInStatus.NotCheckedIn }
          else updates.firstOrNull()
        }
    return when (status) {
      is CheckInStatus.CheckedIn -> CheckInState.Confirmed(status.checkedInAt)
      CheckInStatus.NotCheckedIn -> CheckInState.Stage.READY
      else -> CheckInState.Stage.STATUS_ERROR
    }
  }

  private companion object {
    const val STATUS_TIMEOUT_MILLIS = 10_000L
  }
}
