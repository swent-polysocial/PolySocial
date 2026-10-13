// Contributors: OpenAI Codex (password recovery screen state for #164).
package com.polysocial.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.PasswordResetResult
import com.polysocial.model.auth.isRecoveryEmail
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Transient recovery form; no password, token or reset link is stored. */
data class PasswordRecoveryState(
    val email: String = "",
    val loading: Boolean = false,
    val result: PasswordResetResult? = null,
) {
  val canSubmit: Boolean
    get() =
        !loading && result != PasswordResetResult.Sent && result != PasswordResetResult.InvalidEmail
}

/**
 * Validates and sends one recovery request at a time, independently of login and session routing.
 */
@HiltViewModel
class PasswordRecoveryViewModel @Inject constructor(private val auth: AuthRepository) :
    ViewModel() {
  private val state = MutableStateFlow(PasswordRecoveryState())
  val uiState = state.asStateFlow()

  fun editEmail(email: String) {
    if (!state.value.loading && state.value.result != PasswordResetResult.Sent)
        state.value = PasswordRecoveryState(email)
  }

  /** A fresh visit starts with the login email; the previous result is discarded. */
  fun open(email: String) {
    if (!state.value.loading) state.value = PasswordRecoveryState(email)
  }

  fun submit() {
    val current = state.value
    if (!current.canSubmit) return
    if (!isRecoveryEmail(current.email)) {
      state.value = current.copy(result = PasswordResetResult.InvalidEmail)
      return
    }
    state.value = current.copy(loading = true, result = null)
    viewModelScope.launch {
      val result =
          try {
            auth.sendPasswordResetEmail(current.email.trim())
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            PasswordResetResult.UnexpectedError
          }
      state.value = current.copy(result = result)
    }
  }
}
