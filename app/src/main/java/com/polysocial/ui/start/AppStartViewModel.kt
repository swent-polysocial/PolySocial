// Contributors: Claude (app-start routing for #32; retry only after an error).
package com.polysocial.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the app opens. */
sealed interface StartDestination {
  data object Loading : StartDestination

  data object Login : StartDestination

  data object VerifyEmail : StartDestination

  /** Verified, but no profile yet: the profile step after verification (#34). */
  data object ProfileSetup : StartDestination

  data object Main : StartDestination

  /** The profile could not be loaded; the user can retry. */
  data object Error : StartDestination
}

/**
 * Decides the start destination: no user → Log in; unverified → Verify Email; verified → load the
 * profile once, then the main app, or the profile step when it is missing.
 */
@HiltViewModel
class AppStartViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
) : ViewModel() {
  private val _destination = MutableStateFlow<StartDestination>(StartDestination.Loading)
  val destination: StateFlow<StartDestination> = _destination.asStateFlow()

  init {
    resolve()
  }

  /**
   * Tries again after [StartDestination.Error]. Ignored otherwise, so repeated taps can't load the
   * profile twice.
   */
  fun retry() {
    if (_destination.value == StartDestination.Error) resolve()
  }

  private fun resolve() {
    _destination.value = StartDestination.Loading
    val user = auth.currentUser()
    when {
      user == null -> _destination.value = StartDestination.Login
      !user.isEmailVerified -> _destination.value = StartDestination.VerifyEmail
      else ->
          viewModelScope.launch {
            _destination.value =
                when (profiles.getProfile(user.uid)) {
                  is ProfileResult.Found -> StartDestination.Main
                  ProfileResult.NotFound -> StartDestination.ProfileSetup
                  ProfileResult.NetworkError,
                  ProfileResult.UnexpectedError -> StartDestination.Error
                }
          }
    }
  }
}
