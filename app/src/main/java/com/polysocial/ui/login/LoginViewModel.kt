// Contributors: Claude (Log in screen state for #32); Claude Opus 5.5 (block Log in while
// redirecting).
package com.polysocial.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.LogInResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Log in screen shows; each value matches a Log in frame in Figma. */
sealed interface LoginStatus {
  data object Idle : LoginStatus

  data object Loading : LoginStatus

  data object WrongCredentials : LoginStatus

  /** No connection, or any other unexpected failure. */
  data object CantConnect : LoginStatus

  /** Logged in, but the email is not verified: the screen redirects to Verify Email. */
  data object Unverified : LoginStatus

  /** Logged in with a verified email: the app continues to start routing. */
  data object LoggedIn : LoginStatus
}

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val status: LoginStatus = LoginStatus.Idle,
) {
  /**
   * Log in is enabled only when both fields are filled and no attempt is running or redirecting
   * (after a successful login the screen is leaving, so a second attempt must not start).
   */
  val canSubmit: Boolean
    get() =
        email.isNotBlank() &&
            password.isNotBlank() &&
            status != LoginStatus.Loading &&
            status != LoginStatus.Unverified &&
            status != LoginStatus.LoggedIn
}

/** Holds the Log in form and runs the login attempt through [AuthRepository]. */
@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
  private val _uiState = MutableStateFlow(LoginUiState())
  val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

  fun onEmailChange(email: String) = _uiState.update {
    it.copy(email = email, status = editedStatus(it))
  }

  fun onPasswordChange(password: String) = _uiState.update {
    it.copy(password = password, status = editedStatus(it))
  }

  fun onTogglePasswordVisibility() = _uiState.update {
    it.copy(passwordVisible = !it.passwordVisible)
  }

  fun onLogIn() {
    val state = _uiState.value
    if (!state.canSubmit) return
    _uiState.update { it.copy(status = LoginStatus.Loading) }
    viewModelScope.launch {
      val status =
          when (val result = auth.logIn(state.email.trim(), state.password)) {
            is LogInResult.Success ->
                if (result.user.isEmailVerified) LoginStatus.LoggedIn else LoginStatus.Unverified
            LogInResult.WrongCredentials -> LoginStatus.WrongCredentials
            LogInResult.NetworkError,
            LogInResult.UnexpectedError -> LoginStatus.CantConnect
          }
      _uiState.update { it.copy(status = status) }
    }
  }

  /** Typing again clears a previous error message. */
  private fun editedStatus(state: LoginUiState): LoginStatus =
      when (state.status) {
        LoginStatus.WrongCredentials,
        LoginStatus.CantConnect -> LoginStatus.Idle
        else -> state.status
      }
}
