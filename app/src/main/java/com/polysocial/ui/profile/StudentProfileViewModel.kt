// Contributors: Claude Opus 5.5 (a group member's public profile, #368, and the cached fallback
// after review); Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: OpenAI Codex (6.2 Sol, high; reuse the bounded profile lookup, #360 review).
// Contributors: Claude (Sonnet 5.5, Anthropic) (the read-result mapping shared with My profile,
// #495).
// Contributors: Claude (Opus 5.5, Anthropic) (the friend action on a student's profile, #478);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.getPublicProfileWithCache
import com.polysocial.ui.friend.FriendCard
import com.polysocial.ui.friend.FriendCardController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

/** How a public profile read is shown, for another student's profile and for My profile (#495). */
internal fun PublicProfileResult.toUiState(): StudentProfileUiState =
    when (this) {
      is PublicProfileResult.Found -> StudentProfileUiState.Found(profile)
      PublicProfileResult.NotFound -> StudentProfileUiState.NotFound
      PublicProfileResult.NetworkError,
      PublicProfileResult.UnexpectedError -> StudentProfileUiState.CouldNotLoad
    }

/**
 * How long the server may take before a profile screen shows the cached profile, or Couldn't load:
 * for another student's profile and for My profile (#495).
 */
internal const val PROFILE_READ_TIMEOUT_MILLIS = 5_000L

/**
 * Another student's public profile, opened from a group member's row (#368) with their UID as the
 * route argument [UID_ARG]. It reads only `publicProfiles/{uid}` through [PublicProfileRepository]
 * (name, section, year and interests, never the email), once when the screen opens and again on
 * [retry]. Firestore's cache answers offline when the profile was read before (the group just read
 * it). On a connection that stalls, Firestore waits about ten seconds before it treats the device
 * as offline, so a read that doesn't answer within [PROFILE_READ_TIMEOUT_MILLIS] falls back to the
 * cached profile; without one the screen shows Couldn't load, so it never stays loading. Only the
 * server can say a profile is missing: a cached miss may be out of date. Under another student's
 * profile, [friendCard] offers Add friend, Request sent, Accept request or Friends (#478).
 */
@HiltViewModel
class StudentProfileViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: PublicProfileRepository,
    auth: AuthRepository,
    friends: FriendRepository,
) : ViewModel() {
  private val uid = savedStateHandle.get<String>(UID_ARG)?.takeIf { it.isNotBlank() }
  private val state = MutableStateFlow<StudentProfileUiState>(StudentProfileUiState.Loading)
  val uiState: StateFlow<StudentProfileUiState> = state.asStateFlow()

  private val card = MutableStateFlow<FriendCard?>(null)

  /**
   * The friend action under the profile (#478, Figma #493): the card's button and note from the
   * live friendship. Null while loading, when no profile was found, and on the student's own
   * profile, which offers no action.
   */
  val friendCard: StateFlow<FriendCard?> = card.asStateFlow()

  /**
   * The request logic shared with the scan and Add by username (#529), created only for another
   * student's profile: it starts following the friendships at once, so the button is already right
   * when the profile arrives.
   */
  private val cards: FriendCardController<FriendCard?>? =
      auth
          .currentUser()
          ?.uid
          ?.takeIf { uid != null && it != uid }
          ?.let { myUid ->
            FriendCardController(
                myUid,
                friends,
                viewModelScope,
                card,
                cardOf = { it },
                withCard = { _, changed -> changed },
            )
          }

  init {
    load()
  }

  /** The friend action's button: Add friend sends a request, Accept request accepts theirs. */
  fun onFriendButton() {
    cards?.onButton()
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
      val loaded = profiles.getPublicProfileWithCache(uid, PROFILE_READ_TIMEOUT_MILLIS).toUiState()
      if (loaded is StudentProfileUiState.Found) card.value = cards?.cardFor(loaded.profile)
      state.value = loaded
    }
  }

  companion object {
    /** The route argument holding the student's UID. */
    const val UID_ARG = "studentUid"
  }
}
