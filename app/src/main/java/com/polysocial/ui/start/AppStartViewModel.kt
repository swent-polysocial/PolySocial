// Contributors: Claude (app-start routing for #32; refresh after Log in, not while loading; log
// out and the created profile; Verify Email opened in-session, #74; an association an admin
// hasn't verified yet opens its pending app, #127; a non-EPFL account that is not an association
// fails closed, with the rules' exact @epfl.ch check, #208).
package com.polysocial.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.isExactEpflEmail
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

  /**
   * Verified, with an EPFL email, but no profile yet: the student profile step after verification
   * (#34).
   */
  data object ProfileSetup : StartDestination

  /** The students' app, which a verified association also opens until it has its own (#140). */
  data object Main : StartDestination

  /**
   * An association account an admin hasn't verified yet: its own tabs, in their pending state
   * (#127). Once an admin sets the flag (#135), the next start opens [Main] instead.
   */
  data object PendingAssociation : StartDestination

  /**
   * The profile could not be loaded, or the account can't open any app: a non-EPFL session without
   * an association profile (#208). The user can retry.
   */
  data object Error : StartDestination
}

/**
 * Decides the start destination: no user → Log in; unverified → Verify Email; verified → load the
 * profile once, then the main app, the pending association app for an association an admin hasn't
 * verified yet, or the profile step when the profile is missing. An association is routed by its
 * type whatever its email (an `@epfl.ch` association is still an association, #161); anyone else
 * also needs an exact `@epfl.ch` session email, as the Security Rules check it (lowercase domain).
 *
 * Log in accepts any email so associations can sign in (#208), so routing fails closed: any other
 * session gets [StartDestination.Error] unless its profile is an association's, instead of the
 * student profile step or the students' app. The Security Rules deny such an account student data
 * anyway; this keeps it out of the student screens.
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

  /** The running profile read, cancelled by [logOut] so it can't route the signed-out user. */
  private var profileRead: Job? = null

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
    _destination.value = StartDestination.Login
  }

  private fun resolve() {
    _destination.value = StartDestination.Loading
    val user = auth.currentUser()
    when {
      user == null -> _destination.value = StartDestination.Login
      !user.isEmailVerified -> _destination.value = StartDestination.VerifyEmail
      else -> profileRead = viewModelScope.launch {
            _destination.value =
                when (val result = profiles.getProfile(user.uid)) {
                  is ProfileResult.Found -> result.profile.startDestination(user.email)
                  ProfileResult.NotFound ->
                      if (isExactEpflEmail(user.email)) StartDestination.ProfileSetup
                      else StartDestination.Error
                  ProfileResult.NetworkError,
                  ProfileResult.UnexpectedError -> StartDestination.Error
                }
          }
    }
  }
}

/**
 * Where a signed-in account with this profile and session email opens: an association by its flag,
 * whatever its email; anyone else only with an exact EPFL email.
 */
private fun UserProfile.startDestination(sessionEmail: String): StartDestination =
    when {
      accountType == AccountType.ASSOCIATION && !isAssociationVerified ->
          StartDestination.PendingAssociation
      accountType == AccountType.ASSOCIATION -> StartDestination.Main
      isExactEpflEmail(sessionEmail) -> StartDestination.Main
      else -> StartDestination.Error
    }
