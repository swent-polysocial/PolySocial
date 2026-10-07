// Contributors: Claude (profile step state for #34).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

  /** The profile couldn't be written (offline or another failure); Continue retries. */
  CouldNotSave,

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
  private val _uiState =
      MutableStateFlow(
          auth.currentUser().let {
            ProfileSetupUiState(
                displayName = it?.displayName.orEmpty(),
                email = it?.email.orEmpty(),
            )
          }
      )
  val uiState: StateFlow<ProfileSetupUiState> = _uiState.asStateFlow()

  fun onDisplayNameChange(displayName: String) = edit { it.copy(displayName = displayName) }

  fun onSectionChange(section: String) = edit { it.copy(section = section) }

  fun onYearChange(year: String) = edit { it.copy(year = year) }

  /**
   * Creates the profile once. Does nothing unless [ProfileSetupUiState.canContinue]. An existing
   * profile counts as saved, so the user is never stuck on this step.
   */
  fun onContinue() {
    val state = _uiState.value
    if (!state.canContinue) return
    val user = auth.currentUser()
    if (user == null) {
      _uiState.update { it.copy(status = ProfileSetupStatus.CouldNotSave) }
      return
    }
    _uiState.update { it.copy(status = ProfileSetupStatus.Saving) }
    viewModelScope.launch {
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
            CreateProfileResult.NetworkError,
            CreateProfileResult.UnexpectedError -> ProfileSetupStatus.CouldNotSave
          }
      _uiState.update { it.copy(status = status) }
    }
  }

  /** Applies a form change; after a failed save, editing clears the error message. */
  private fun edit(change: (ProfileSetupUiState) -> ProfileSetupUiState) = _uiState.update {
    if (it.status == ProfileSetupStatus.Saving || it.status == ProfileSetupStatus.Saved) it
    else change(it).copy(status = ProfileSetupStatus.Editing)
  }
}
