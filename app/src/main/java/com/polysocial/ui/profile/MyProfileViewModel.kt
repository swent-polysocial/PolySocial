// Contributors: Claude (Sonnet 5.5, Anthropic) (the signed-in student's own profile, #495).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.getPublicProfileWithCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The signed-in student's own profile as other students see it (#495): their
 * `publicProfiles/{uid}`, read through the same bounded lookup as another student's profile
 * ([StudentProfileViewModel]), with the same states. A missing public copy is
 * [StudentProfileUiState.NotFound], which the screen words for the student's own profile instead of
 * saying the account can't be found (#291).
 *
 * The screen calls [refresh] each time it comes back to the foreground, so a profile just saved in
 * Edit profile shows at once; the content on screen stays until the new read answers.
 */
@HiltViewModel
class MyProfileViewModel
@Inject
constructor(private val auth: AuthRepository, private val profiles: PublicProfileRepository) :
    ViewModel() {
  private val state = MutableStateFlow<StudentProfileUiState>(StudentProfileUiState.Loading)
  val uiState: StateFlow<StudentProfileUiState> = state.asStateFlow()
  private var read: Job? = null

  /** Reads the profile again; a profile already shown stays until the answer arrives. */
  fun refresh() {
    val uid = auth.currentUser()?.uid
    if (uid == null) {
      state.value = StudentProfileUiState.CouldNotLoad
      return
    }
    read?.cancel()
    read = viewModelScope.launch {
      state.value = profiles.getPublicProfileWithCache(uid, PROFILE_READ_TIMEOUT_MILLIS).toUiState()
    }
  }

  /** Reads the profile again after it couldn't load; does nothing in any other state. */
  fun retry() {
    if (state.value != StudentProfileUiState.CouldNotLoad) return
    state.value = StudentProfileUiState.Loading
    refresh()
  }
}
