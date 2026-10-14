// Contributors: OpenAI Codex (confirmation and logout state, #109).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Logout failure blocks dismissal until cleanup succeeds, preventing reuse of a dirty cache. */
enum class LogoutState {
  Idle,
  Confirming,
  Clearing,
  Error,
  LoggedOut,
}

/** Owns Profile's sole action, keeping Firebase and cache cleanup outside the UI. */
@HiltViewModel
class ProfileViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val cache: SessionCache,
) : ViewModel() {
  private val state = MutableStateFlow(LogoutState.Idle)
  val uiState = state.asStateFlow()

  fun requestLogout() {
    if (state.value == LogoutState.Idle) state.value = LogoutState.Confirming
  }

  fun cancelLogout() {
    if (state.value == LogoutState.Confirming) state.value = LogoutState.Idle
  }

  fun confirmLogout() {
    if (state.value != LogoutState.Confirming && state.value != LogoutState.Error) return
    state.value = LogoutState.Clearing
    viewModelScope.launch {
      try {
        if (cache.clear() == CacheClearResult.Cleared) {
          auth.logOut()
          state.value = LogoutState.LoggedOut
        } else state.value = LogoutState.Error
      } catch (cancelled: CancellationException) {
        state.value = LogoutState.Error
        throw cancelled
      } catch (_: Exception) {
        state.value = LogoutState.Error
      }
    }
  }
}
