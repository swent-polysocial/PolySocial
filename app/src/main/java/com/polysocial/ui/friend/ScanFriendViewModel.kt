// Contributors: Claude Opus 5.5 (drafted the scan-a-friend ViewModel, #359); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.SendFriendRequestResult
import com.polysocial.model.qr.QrPayload
import com.polysocial.model.qr.parseQrPayload
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the scanned student's card offers, from the live friendship with them (#357). */
enum class FriendButton {
  /** No friendship yet: "Add friend" sends a request. */
  ADD,

  /** The student sent them a request: "Request sent", nothing to tap. */
  SENT,

  /** They sent the student a request: "Accept request". */
  ACCEPT,

  /** They are friends: "Friends", nothing to tap. */
  FRIENDS,
}

/** Why a scan opened no card: Figma's sheets over the paused camera. */
enum class ScanProblem {
  /** Not a PolySocial friend code: a web link, another app's code, a check-in code, a bad scan. */
  INVALID_CODE,

  /** The student's own friend code. */
  OWN_CODE,

  /** No public profile under that UID: an association, a deleted account or a made-up code. */
  NOT_FOUND,

  /** The profile couldn't be read: offline and not cached, or another failure. */
  COULD_NOT_LOAD,
}

/** Why the card's button didn't work. Nothing was written or queued in either case. */
enum class FriendActionError {
  OFFLINE,
  FAILED,
}

/** What Scan a friend's code shows (Figma section 15, #336, and its #359 states). */
sealed interface ScanFriendUiState {
  /** The camera looks for a code. */
  data object Scanning : ScanFriendUiState

  /** A friend code was read; their public profile is loading, and the camera is paused. */
  data object Loading : ScanFriendUiState

  /** The scan opened no card; the camera is paused until Scan again. */
  data class Problem(val problem: ScanProblem) : ScanFriendUiState

  /**
   * The scanned student's public [profile], with the [button] the live friendship gives. [busy]
   * while a tap's request runs; [error] after one failed.
   */
  data class Card(
      val profile: PublicProfile,
      val button: FriendButton,
      val busy: Boolean = false,
      val error: FriendActionError? = null,
  ) : ScanFriendUiState
}

/**
 * Scan a friend's code (#359). The camera (behind `QrScanner`) hands each code it reads to
 * [onCodeScanned] as raw text; the first one is handled and the rest ignored until [onScanAgain],
 * since the camera reports the same code many times a second. A friend code (#337's
 * `parseQrPayload`) that isn't the student's own opens that student's public profile, read by UID
 * (#229); anything else is a [ScanProblem].
 *
 * The card's button follows the student's friendships as `observeFriendships` reports them (#357),
 * so it changes by itself when the other student accepts or sends a request. [onButton] sends a
 * request or accepts theirs; a failure is shown on the card and nothing is queued, so tapping again
 * tries again.
 */
@HiltViewModel
class ScanFriendViewModel
@Inject
constructor(
    auth: AuthRepository,
    private val profiles: PublicProfileRepository,
    private val friends: FriendRepository,
) : ViewModel() {
  private val myUid: String? = auth.currentUser()?.uid
  private val state = MutableStateFlow<ScanFriendUiState>(ScanFriendUiState.Scanning)
  val uiState: StateFlow<ScanFriendUiState> = state.asStateFlow()

  /** The student's friendships, as last delivered by the listener; empty until it delivers. */
  private var friendships: List<Friendship> = emptyList()

  init {
    viewModelScope.launch {
      friends.observeFriendships().collect { result ->
        // A failed listener leaves the last known friendships: Add friend still works, and a
        // request that already exists comes back as AlreadyExists.
        if (result is FriendshipsResult.Success) {
          friendships = result.friendships
          state.update { if (it is ScanFriendUiState.Card) it.copy(button = buttonFor(it)) else it }
        }
      }
    }
  }

  /** Handles the camera's first code since scanning (re)started; ignores the rest. */
  fun onCodeScanned(raw: String) {
    if (state.value != ScanFriendUiState.Scanning) return
    val uid = (parseQrPayload(raw) as? QrPayload.Friend)?.uid
    state.value =
        when (uid) {
          null -> ScanFriendUiState.Problem(ScanProblem.INVALID_CODE)
          myUid -> ScanFriendUiState.Problem(ScanProblem.OWN_CODE)
          else -> ScanFriendUiState.Loading
        }
    if (uid == null || uid == myUid) return
    viewModelScope.launch {
      state.value =
          when (val result = profiles.getPublicProfile(uid)) {
            is PublicProfileResult.Found -> {
              val card = ScanFriendUiState.Card(result.profile, FriendButton.ADD)
              card.copy(button = buttonFor(card))
            }
            PublicProfileResult.NotFound -> ScanFriendUiState.Problem(ScanProblem.NOT_FOUND)
            PublicProfileResult.NetworkError,
            PublicProfileResult.UnexpectedError ->
                ScanFriendUiState.Problem(ScanProblem.COULD_NOT_LOAD)
          }
    }
  }

  /** Scan again: from a problem's sheet, or after a card. */
  fun onScanAgain() {
    state.value = ScanFriendUiState.Scanning
  }

  /** The card's button: Add friend sends a request, Accept request accepts theirs. */
  fun onButton() {
    val card = state.value as? ScanFriendUiState.Card ?: return
    if (card.busy) return
    val uid = card.profile.uid
    when (card.button) {
      FriendButton.ADD -> act(card) { sent(friends.sendRequest(uid)) }
      FriendButton.ACCEPT -> {
        val request = friendshipWith(uid) ?: return
        act(card) { accepted(friends.accept(request)) }
      }
      FriendButton.SENT,
      FriendButton.FRIENDS -> Unit
    }
  }

  /** Runs [call] with the card busy, then shows the button and error it returns. */
  private fun act(card: ScanFriendUiState.Card, call: suspend () -> Outcome) {
    state.value = card.copy(busy = true, error = null)
    viewModelScope.launch {
      val outcome = call()
      state.update { current ->
        if (current !is ScanFriendUiState.Card) return@update current
        current.copy(
            busy = false,
            button = outcome.button ?: buttonFor(current),
            error = outcome.error,
        )
      }
    }
  }

  /** A tap's result: the button it proves (null to keep the listener's) and its error. */
  private data class Outcome(val button: FriendButton?, val error: FriendActionError? = null)

  private fun sent(result: SendFriendRequestResult) =
      when (result) {
        // Shown at once: the listener confirms it a moment later.
        SendFriendRequestResult.Sent -> Outcome(FriendButton.SENT)
        SendFriendRequestResult.AlreadyExists -> Outcome(null)
        SendFriendRequestResult.NetworkError -> Outcome(null, FriendActionError.OFFLINE)
        SendFriendRequestResult.UnexpectedError -> Outcome(null, FriendActionError.FAILED)
      }

  private fun accepted(result: FriendshipActionResult) =
      when (result) {
        FriendshipActionResult.Done -> Outcome(FriendButton.FRIENDS)
        // Cancelled or changed since: the listener shows what it is now.
        FriendshipActionResult.Gone -> Outcome(null)
        FriendshipActionResult.NetworkError -> Outcome(null, FriendActionError.OFFLINE)
        FriendshipActionResult.UnexpectedError -> Outcome(null, FriendActionError.FAILED)
      }

  private fun friendshipWith(uid: String): Friendship? = friendships.firstOrNull {
    uid in it.uids && myUid in it.uids
  }

  private fun buttonFor(card: ScanFriendUiState.Card): FriendButton {
    val friendship = friendshipWith(card.profile.uid) ?: return FriendButton.ADD
    return when {
      friendship.status == FriendshipStatus.ACCEPTED -> FriendButton.FRIENDS
      friendship.requestedBy == myUid -> FriendButton.SENT
      else -> FriendButton.ACCEPT
    }
  }
}
