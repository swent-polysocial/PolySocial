// Contributors: OpenAI Codex (session-scoped Organization loading through the shared logout
// controller, #140).
// Contributors: OpenAI Codex (confirmation and logout state, #109).
// Contributors: Claude (reset for the next session, #127).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

sealed interface OrganizationUiState {
  data object Loading : OrganizationUiState

  data class Ready(val association: Association) : OrganizationUiState

  data object Error : OrganizationUiState
}

/** Shares cache-first logout and explicit own-organization loading between both account shells. */
@HiltViewModel
class ProfileViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val cache: SessionCache,
    private val associations: AssociationRepository,
) : ViewModel() {
  private val state = MutableStateFlow(LogoutState.Idle)
  val uiState = state.asStateFlow()
  private val organizationState = MutableStateFlow<OrganizationUiState>(OrganizationUiState.Loading)
  val organization = organizationState.asStateFlow()
  private var organizationRead: Job? = null
  private var organizationUid: String? = null
  private var organizationGeneration = 0

  /**
   * Reads the current account's identity once per session; failed reads permit an explicit Retry.
   */
  fun loadOrganization() {
    val user = auth.currentUser()
    if (user == null || !user.isEmailVerified) {
      clearOrganization()
      organizationState.value = OrganizationUiState.Error
      return
    }
    if (
        organizationUid == user.uid &&
            (organizationRead?.isActive == true ||
                organizationState.value is OrganizationUiState.Ready)
    )
        return
    clearOrganization()
    organizationUid = user.uid
    val generation = organizationGeneration
    organizationRead = viewModelScope.launch {
      try {
        val result = associations.getAssociation(user.uid)
        if (generation == organizationGeneration) {
          val current = auth.currentUser()
          organizationState.value =
              if (current?.uid != user.uid || !current.isEmailVerified) OrganizationUiState.Error
              else
                  when (result) {
                    is AssociationResult.Found -> OrganizationUiState.Ready(result.association)
                    AssociationResult.NotFound,
                    AssociationResult.NetworkError,
                    AssociationResult.UnexpectedError -> OrganizationUiState.Error
                  }
        }
      } catch (cancelled: CancellationException) {
        if (generation == organizationGeneration)
            organizationState.value = OrganizationUiState.Error
        throw cancelled
      } catch (_: Exception) {
        if (generation == organizationGeneration)
            organizationState.value = OrganizationUiState.Error
      }
    }
  }

  private fun clearOrganization() {
    organizationGeneration++
    organizationRead?.cancel()
    organizationRead = null
    organizationUid = null
    organizationState.value = OrganizationUiState.Loading
  }

  fun requestLogout() {
    if (state.value == LogoutState.Idle) state.value = LogoutState.Confirming
  }

  fun cancelLogout() {
    if (state.value == LogoutState.Confirming) state.value = LogoutState.Idle
  }

  /**
   * Back to [LogoutState.Idle] once the logged-out app was left (#127). The association's app gets
   * this ViewModel from the activity, which outlives the session, so the next account must not
   * start "logged out". Ignored unless logged out.
   */
  fun reset() {
    if (state.value == LogoutState.LoggedOut) state.value = LogoutState.Idle
  }

  fun confirmLogout() {
    if (state.value != LogoutState.Confirming && state.value != LogoutState.Error) return
    state.value = LogoutState.Clearing
    viewModelScope.launch {
      try {
        if (cache.clear() == CacheClearResult.Cleared) {
          auth.logOut()
          clearOrganization()
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
