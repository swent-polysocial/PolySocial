// Contributors: Claude (profile step state for #34; unverified accounts refused after review;
// reset on sign-out, #74; generic save error, #142).
// Contributors: OpenAI Codex (Google identity prefill on profile entry for #107).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the profile step is; each value matches a Profile frame in Figma. */
enum class ProfileSetupStatus {
  /** Filling in the form (the default, ready and choosing frames). */
  Editing,

  /** Continue was tapped and the profile is being written. */
  Saving,

  /** The profile couldn't be written because the device is offline; Continue retries. */
  CouldNotSave,

  /**
   * The profile couldn't be written for another reason (an unexpected failure, or an account that
   * is signed out or not verified, for example a rules denial with a sign-in token from before the
   * verification), where retrying doesn't help: the student is offered to log out and in again.
   */
  CouldNotSaveGeneric,

  /** The profile exists: the app continues to the main screen. */
  Saved,
}

data class ProfileSetupUiState(
    val displayName: String = "",
    val email: String = "",
    val section: String? = null,
    val year: String? = null,
    val status: ProfileSetupStatus = ProfileSetupStatus.Editing,
) {
  /**
   * Continue is enabled once a name, a section and a year are set, and while no save is running or
   * done (a second tap must not write the profile twice).
   */
  val canContinue: Boolean
    get() =
        displayName.isNotBlank() &&
            section != null &&
            year != null &&
            status != ProfileSetupStatus.Saving &&
            status != ProfileSetupStatus.Saved
}

/**
 * Holds the profile step that follows email verification, and creates the user's profile through
 * [UserProfileRepository] when Continue is tapped. The name and the verified email come from the
 * signed-in account.
 */
@HiltViewModel
class ProfileSetupViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
) : ViewModel() {
  private val _uiState = MutableStateFlow(stateForCurrentAccount())
  val uiState: StateFlow<ProfileSetupUiState> = _uiState.asStateFlow()

  /** The running save, cancelled by [reset]. */
  private var save: Job? = null

  /**
   * Starts the step again for the account signed in now (an empty form when nobody is), with no
   * save running. The app calls it when the step opens and when the user signs out, since this
   * ViewModel outlives the step: no name or email of a previous account stays in it.
   */
  fun reset() {
    save?.cancel()
    _uiState.value = stateForCurrentAccount()
  }

  fun onDisplayNameChange(displayName: String) = edit { it.copy(displayName = displayName) }

  fun onSectionChange(section: String) = edit { it.copy(section = section) }

  fun onYearChange(year: String) = edit { it.copy(year = year) }

  /**
   * Creates the profile once. Does nothing unless [ProfileSetupUiState.canContinue]. An existing
   * profile counts as saved, so the user is never stuck on this step. A signed-out or unverified
   * account never reaches the repository: it gets [ProfileSetupStatus.CouldNotSaveGeneric]. Only a
   * network error gets [ProfileSetupStatus.CouldNotSave], whose Continue retries.
   */
  fun onContinue() {
    val state = _uiState.value
    if (!state.canContinue) return
    // Only a verified account may get a profile (#34); routing (#74) and the rules (#35) check it
    // too, but the step must not rely on them.
    val user = auth.currentUser()
    if (user == null || !user.isEmailVerified) {
      _uiState.update { it.copy(status = ProfileSetupStatus.CouldNotSaveGeneric) }
      return
    }
    _uiState.update { it.copy(status = ProfileSetupStatus.Saving) }
    save = viewModelScope.launch {
      val profile =
          UserProfile(
              uid = user.uid,
              email = user.email,
              displayName = state.displayName.trim(),
              section = checkNotNull(state.section),
              year = checkNotNull(state.year),
          )
      val status =
          when (profiles.createProfile(profile)) {
            CreateProfileResult.Created,
            CreateProfileResult.AlreadyExists -> ProfileSetupStatus.Saved
            CreateProfileResult.NetworkError -> ProfileSetupStatus.CouldNotSave
            CreateProfileResult.UnexpectedError -> ProfileSetupStatus.CouldNotSaveGeneric
          }
      _uiState.update { it.copy(status = status) }
    }
  }

  private fun stateForCurrentAccount(): ProfileSetupUiState =
      auth.currentUser().let {
        ProfileSetupUiState(displayName = it?.displayName.orEmpty(), email = it?.email.orEmpty())
      }

  /** Applies a form change; after a failed save, editing clears the error message. */
  private fun edit(change: (ProfileSetupUiState) -> ProfileSetupUiState) = _uiState.update {
    if (it.status == ProfileSetupStatus.Saving || it.status == ProfileSetupStatus.Saved) it
    else change(it).copy(status = ProfileSetupStatus.Editing)
  }
}
