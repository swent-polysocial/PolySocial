// Contributors: Claude Opus 5.5 (drafted the scan-a-friend ViewModel, #359, and its review
// fixes); Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Opus 5.5, Anthropic) (its card moved to the shared FriendCardController,
// #452); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.friend.FriendRepository
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
import kotlinx.coroutines.launch

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

  /** The camera couldn't start: none on the device, or another app holds it. */
  CAMERA_FAILED,
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
    friends: FriendRepository,
) : ViewModel() {
  private val myUid: String? = auth.currentUser()?.uid
  private val state = MutableStateFlow<ScanFriendUiState>(ScanFriendUiState.Scanning)
  val uiState: StateFlow<ScanFriendUiState> = state.asStateFlow()

  /** The card's friend button and its requests, shared with Add by username (#452). */
  private val card =
      FriendCardController(
          myUid,
          friends,
          viewModelScope,
          state,
          cardOf = { (it as? ScanFriendUiState.Card)?.toFriendCard() },
          withCard = { _, card -> card.toScanCard() },
      )

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
            is PublicProfileResult.Found -> card.cardFor(result.profile).toScanCard()
            PublicProfileResult.NotFound -> ScanFriendUiState.Problem(ScanProblem.NOT_FOUND)
            PublicProfileResult.NetworkError,
            PublicProfileResult.UnexpectedError ->
                ScanFriendUiState.Problem(ScanProblem.COULD_NOT_LOAD)
          }
    }
  }

  /**
   * The camera couldn't start. Shown only while scanning: once a code was read the camera is no
   * longer needed, so a late error must not hide the profile or its sheet.
   */
  fun onCameraError() {
    if (state.value == ScanFriendUiState.Scanning) {
      state.value = ScanFriendUiState.Problem(ScanProblem.CAMERA_FAILED)
    }
  }

  /** Scan again: from a problem's sheet, or after a card. */
  fun onScanAgain() {
    state.value = ScanFriendUiState.Scanning
  }

  /** The card's button: Add friend sends a request, Accept request accepts theirs. */
  fun onButton() = card.onButton()
}

/** This card as the shared [FriendCard]. */
internal fun ScanFriendUiState.Card.toFriendCard() = FriendCard(profile, button, busy, error)

private fun FriendCard.toScanCard() = ScanFriendUiState.Card(profile, button, busy, error)
