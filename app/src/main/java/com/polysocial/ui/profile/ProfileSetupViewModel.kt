// Contributors: Claude (profile step state for #34; unverified accounts refused after review;
// reset on sign-out, #74; generic save error, #142; the full name's limit, test-first, #143; the
// interests step, test-first, #230).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.INTERESTS
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.model.user.isDisplayNameTooLong
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which screen of the profile step shows (#230). The step writes the profile once, from the second
 * screen, so a student who leaves before Finish has no profile and starts the step again.
 */
enum class ProfileSetupPage {
  /**
   * Step 1 of 2: name, section and year (the Figma "Profile · …" frames). Continue saves nothing.
   */
  Details,

  /** Step 2 of 2: interests, and Finish, which saves (the Figma "Interests · …" frames). */
  Interests,
}

/** Where the save is; each value matches a Figma frame. Only the interests screen saves. */
enum class ProfileSetupStatus {
  /** Filling in the form (the default, ready and choosing frames). */
  Editing,

  /** Finish was tapped and the profile is being written. */
  Saving,

  /** The profile couldn't be written because the device is offline; Finish retries. */
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
    /** Codes from [INTERESTS]; none to all of them may be chosen. */
    val interests: Set<String> = emptySet(),
    val page: ProfileSetupPage = ProfileSetupPage.Details,
    val status: ProfileSetupStatus = ProfileSetupStatus.Editing,
) {
  /** The name is over the full name's limit (#143): the step shows an error and can't continue. */
  val nameTooLong: Boolean
    get() = isDisplayNameTooLong(displayName)

  /**
   * Continue (to the interests screen) is enabled once a name within the limit, a section and a
   * year are set.
   */
  val canContinue: Boolean
    get() = displayName.isNotBlank() && !nameTooLong && section != null && year != null

  /**
   * Finish is enabled with any choice of interests, none included, while no save is running or done
   * (a second tap must not write the profile twice).
   */
  val canFinish: Boolean
    get() = canContinue && status != ProfileSetupStatus.Saving && status != ProfileSetupStatus.Saved

  /** While saving or saved, the form is locked and Back does nothing. */
  val locked: Boolean
    get() = status == ProfileSetupStatus.Saving || status == ProfileSetupStatus.Saved
}

/**
 * Holds the profile step that follows email verification: its details screen, then its interests
 * screen (#230), which creates the user's profile through [UserProfileRepository] when Finish is
 * tapped. The name and the verified email come from the signed-in account.
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
   * Chooses the interest [code], or unchooses it if chosen. A code not in [INTERESTS] is ignored.
   */
  fun onInterestToggle(code: String) {
    if (code !in INTERESTS) return
    edit {
      it.copy(interests = if (code in it.interests) it.interests - code else it.interests + code)
    }
  }

  /** Opens the interests screen once the details are complete. Nothing is saved yet. */
  fun onContinue() = _uiState.update {
    if (it.page == ProfileSetupPage.Details && it.canContinue)
        it.copy(page = ProfileSetupPage.Interests, status = ProfileSetupStatus.Editing)
    else it
  }

  /**
   * Back from the interests screen to the details, keeping everything chosen and clearing a save
   * error. Ignored while saving or saved, so the save's result is never dropped.
   */
  fun onBackToDetails() = _uiState.update {
    if (it.locked) it
    else it.copy(page = ProfileSetupPage.Details, status = ProfileSetupStatus.Editing)
  }

  /**
   * Creates the profile once, with the chosen interests. Does nothing unless the interests screen
   * shows and [ProfileSetupUiState.canFinish]. An existing profile counts as saved, so the user is
   * never stuck on this step. A signed-out or unverified account never reaches the repository: it
   * gets [ProfileSetupStatus.CouldNotSaveGeneric]. Only a network error gets
   * [ProfileSetupStatus.CouldNotSave], whose Finish retries.
   */
  fun onFinish() {
    val state = _uiState.value
    if (state.page != ProfileSetupPage.Interests || !state.canFinish) return
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
              interests = state.interests,
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
    if (it.locked) it else change(it).copy(status = ProfileSetupStatus.Editing)
  }
}
