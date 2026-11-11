// Contributors: Claude Opus 5.5 (drafted the My friend code ViewModel, #339); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.qr.QrCode
import com.polysocial.model.qr.encodeQrCode
import com.polysocial.model.qr.friendQrPayload
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What My friend code shows (Figma section 15, #336). */
sealed interface FriendCodeUiState {
  /**
   * The student's own [code], under their [name] and [details] ("IN · BA3"). Both are empty until
   * known: the code never waits for them.
   */
  data class Shown(val code: QrCode, val name: String, val details: String) : FriendCodeUiState

  /** No code can be drawn for this account (Figma "My friend code · couldn't show", #339). */
  data object CouldNotShow : FriendCodeUiState
}

/**
 * The signed-in student's friend QR code (#339). The code holds only `polysocial://friend/{uid}`,
 * built by #337's [friendQrPayload], so it needs no network: it shows at once, offline too. The
 * name, section and year come from the student's own profile afterwards; if that read fails (for
 * example offline without a cached copy), the account's name stays and the code is still shown.
 *
 * Associations have no friend code: the Profile row is only offered to students, and an account
 * whose profile turns out to be an association's gets [FriendCodeUiState.CouldNotShow].
 */
@HiltViewModel
class FriendCodeViewModel
@Inject
constructor(
    auth: AuthRepository,
    private val profiles: UserProfileRepository,
) : ViewModel() {
  private val state: MutableStateFlow<FriendCodeUiState>
  val uiState: StateFlow<FriendCodeUiState>

  init {
    val user = auth.currentUser()
    // Encoding a payload this short takes well under a millisecond, so it runs here, not on a
    // background dispatcher.
    val code = user?.uid?.let(::friendQrPayload)?.let(::encodeQrCode)
    state =
        MutableStateFlow(
            if (user == null || code == null) FriendCodeUiState.CouldNotShow
            else FriendCodeUiState.Shown(code, user.displayName.orEmpty(), details = "")
        )
    uiState = state.asStateFlow()
    if (user != null && code != null) loadHeader(user.uid)
  }

  private fun loadHeader(uid: String) {
    viewModelScope.launch {
      val profile = (profiles.getProfile(uid) as? ProfileResult.Found)?.profile ?: return@launch
      state.update { current ->
        when {
          current !is FriendCodeUiState.Shown -> current
          profile.accountType == AccountType.ASSOCIATION -> FriendCodeUiState.CouldNotShow
          else ->
              current.copy(
                  name = profile.displayName,
                  details = profileDetails(profile.section, profile.year),
              )
        }
      }
    }
  }
}

/** "IN · BA3", or whichever of the two is set, or "" when neither is. */
internal fun profileDetails(section: String, year: String): String =
    listOf(section, year).filter { it.isNotBlank() }.joinToString(" · ")
