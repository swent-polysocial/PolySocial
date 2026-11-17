// Contributors: Claude Opus 5.5 (a group member's public profile, #368, and the cached fallback
// after review); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a student's public profile screen shows. */
sealed interface StudentProfileUiState {
  data object Loading : StudentProfileUiState

  data class Found(val profile: PublicProfile) : StudentProfileUiState

  /**
   * The server confirmed there is no public profile: a deleted account, a wrong UID, or a profile
   * saved before #229 and not since (#291), so the screen doesn't say the account is gone.
   */
  data object NotFound : StudentProfileUiState

  /** The read failed or didn't answer in time; Retry reads it again. */
  data object CouldNotLoad : StudentProfileUiState
}

/**
 * Another student's public profile, opened from a group member's row (#368) with their UID as the
 * route argument [UID_ARG]. It reads only `publicProfiles/{uid}` through [PublicProfileRepository]
 * (name, section, year and interests, never the email), once when the screen opens and again on
 * [retry]. Firestore's cache answers offline when the profile was read before (the group just read
 * it). On a connection that stalls, Firestore waits about ten seconds before it treats the device
 * as offline, so a read that doesn't answer within [TIMEOUT_MILLIS] falls back to the cached
 * profile; without one the screen shows Couldn't load, so it never stays loading. Only the server
 * can say a profile is missing: a cached miss may be out of date.
 */
@HiltViewModel
class StudentProfileViewModel
@Inject
constructor(savedStateHandle: SavedStateHandle, private val profiles: PublicProfileRepository) :
    ViewModel() {
  private val uid = savedStateHandle.get<String>(UID_ARG)?.takeIf { it.isNotBlank() }
  private val state = MutableStateFlow<StudentProfileUiState>(StudentProfileUiState.Loading)
  val uiState: StateFlow<StudentProfileUiState> = state.asStateFlow()

  init {
    load()
  }

  /** Reads the profile again after it couldn't load; does nothing in any other state. */
  fun retry() {
    if (state.value == StudentProfileUiState.CouldNotLoad) load()
  }

  private fun load() {
    val uid =
        uid
            ?: run {
              state.value = StudentProfileUiState.NotFound
              return
            }
    state.value = StudentProfileUiState.Loading
    viewModelScope.launch {
      val result =
          withTimeoutOrNull(TIMEOUT_MILLIS) { profiles.getPublicProfile(uid) }
              ?: profiles.getCachedPublicProfile(uid).takeIf { it is PublicProfileResult.Found }
      state.value =
          when (result) {
            is PublicProfileResult.Found -> StudentProfileUiState.Found(result.profile)
            PublicProfileResult.NotFound -> StudentProfileUiState.NotFound
            PublicProfileResult.NetworkError,
            PublicProfileResult.UnexpectedError,
            null -> StudentProfileUiState.CouldNotLoad
          }
    }
  }

  companion object {
    /** The route argument holding the student's UID. */
    const val UID_ARG = "studentUid"

    /**
     * How long the server may take before the screen shows the cached profile, or Couldn't load.
     */
    const val TIMEOUT_MILLIS = 5_000L
  }
}
