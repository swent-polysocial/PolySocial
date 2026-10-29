// Contributors: OpenAI Codex (association onboarding destinations and fail-closed routing, #125).
// Contributors: Claude (app-start routing for #32; refresh after Log in, not while loading; log
// out and the created profile; Verify Email opened in-session, #74; an association an admin
// hasn't verified yet opens its pending app, #127).
package com.polysocial.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.isEpflEmail
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

  data object AssociationSetup : StartDestination

  data object AssociationMain : StartDestination

  data object Main : StartDestination

  /**
   * An association account an admin hasn't verified yet: its own tabs, in their pending state
   * (#127). Once an admin sets the flag (#135), the next start opens [Main] instead.
   */
  data object PendingAssociation : StartDestination

  /** The profile could not be loaded; the user can retry. */
  data object Error : StartDestination
}

/**
 * Decides the start destination: no user → Log in; unverified → Verify Email; verified → load the
 * profile once, then the main app, the pending association app for an association an admin hasn't
 * verified yet, or the profile step when the profile is missing. The account type decides, never
 * the email: an association with an `@epfl.ch` email is still an association (#161).
 */
@HiltViewModel
class AppStartViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
    private val associationSignup: AssociationSignupStore = EmptyAssociationSignupStore,
    private val associations: AssociationRepository? = null,
) : ViewModel() {
  private val _destination = MutableStateFlow<StartDestination>(StartDestination.Loading)
  val destination: StateFlow<StartDestination> = _destination.asStateFlow()

  /** The running profile read, cancelled by [logOut] so it can't route the signed-out user. */
  private var profileRead: Job? = null

  private val _accountType = MutableStateFlow(AccountType.STUDENT)
  val accountType: StateFlow<AccountType> = _accountType.asStateFlow()

  init {
    resolve()
  }

  /**
   * Decides the destination again from the current account, for example after Log in or as Retry
   * after [StartDestination.Error]. Ignored while the profile is loading, so repeated calls can't
   * load it twice.
   */
  fun refresh() {
    if (_destination.value != StartDestination.Loading) resolve()
  }

  /**
   * The profile step created the profile (or found that it exists): opens the main app without
   * reading the profile again. Ignored unless the profile step is the current destination.
   */
  fun onProfileCreated() {
    if (_destination.value == StartDestination.ProfileSetup)
        _destination.value = StartDestination.Main
  }

  /**
   * Sign-up or an unverified log in opened Verify Email in this session: routes there too, so a
   * later [logOut] moves the destination back to [StartDestination.Login] and the signed-out start
   * resets the forms. Ignored unless the user was signed out at the start.
   */
  fun onVerificationNeeded() {
    if (_destination.value == StartDestination.Login)
        _destination.value = StartDestination.VerifyEmail
  }

  /**
   * Signs the user out and routes to the signed-out start. A profile read still running is
   * cancelled, so its result can't open the main app or the profile step afterwards.
   */
  fun logOut() {
    profileRead?.cancel()
    auth.logOut()
    _accountType.value = AccountType.STUDENT
    _destination.value = StartDestination.Login
  }

  private fun resolve() {
    _destination.value = StartDestination.Loading
    _accountType.value = AccountType.STUDENT
    val user = auth.currentUser()
    when {
      user == null -> _destination.value = StartDestination.Login
      !user.isEmailVerified -> profileRead = viewModelScope.launch {
            try {
              _accountType.value =
                  if (associationSignup.contains(user.email)) AccountType.ASSOCIATION
                  else AccountType.STUDENT
              _destination.value = StartDestination.VerifyEmail
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Exception) {
              _destination.value = StartDestination.Error
            }
          }
      else -> profileRead = viewModelScope.launch {
            _destination.value =
                try {
                  when (val result = profiles.getProfile(user.uid)) {
                    is ProfileResult.Found -> resolveProfile(user, result.profile)
                    ProfileResult.NotFound ->
                        if (associationSignup.contains(user.email))
                            StartDestination.AssociationSetup
                        else if (isEpflEmail(user.email)) StartDestination.ProfileSetup
                        else StartDestination.Error
                    ProfileResult.NetworkError,
                    ProfileResult.UnexpectedError -> StartDestination.Error
                  }
                } catch (cancelled: CancellationException) {
                  throw cancelled
                } catch (_: Exception) {
                  StartDestination.Error
                }
          }
    }
  }

  private suspend fun resolveProfile(user: AuthUser, profile: UserProfile): StartDestination {
    if (profile.accountType != AccountType.ASSOCIATION) {
      return if (isEpflEmail(user.email)) StartDestination.Main else StartDestination.Error
    }
    associationSignup.clear(user.email)
    return when (associations?.getAssociation(user.uid)) {
      is AssociationResult.Found ->
          if (profile.isAssociationVerified) StartDestination.AssociationMain
          else StartDestination.PendingAssociation
      AssociationResult.NotFound -> StartDestination.AssociationSetup
      else -> StartDestination.Error
    }
  }
}

