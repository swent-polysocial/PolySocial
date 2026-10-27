// Contributors: Claude (which Events tab an account sees, #132).
package com.polysocial.ui.event.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which Events tab the signed-in account sees. */
enum class EventsTabState {
  /** The profile is being read. */
  LOADING,

  /**
   * The upcoming public events, for a student (and any account that isn't a verified association).
   */
  STUDENT,

  /** The association's own events (#132): the Security Rules deny it the public list. */
  ASSOCIATION,

  /** The profile couldn't be read; the screen offers Retry. */
  ERROR,
}

/**
 * Reads the signed-in account's profile once to decide which Events tab it sees: a verified
 * association sees its own events, everyone else the public upcoming events. An unverified
 * association sees the student tab, whose query the rules deny, until #127 shows it its pending
 * state. Nobody signed in, or no profile, also gets the student tab, which then shows its own
 * error. The read uses Firestore's offline cache, so the tab also opens offline once the profile
 * was read at app start.
 */
@HiltViewModel
class EventsTabViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
) : ViewModel() {
  private val _state = MutableStateFlow(EventsTabState.LOADING)
  val state: StateFlow<EventsTabState> = _state.asStateFlow()

  private var read: Job? = null

  init {
    readProfile()
  }

  /** Reads the profile again after an error. Does nothing in any other state. */
  fun retry() {
    if (_state.value == EventsTabState.ERROR) readProfile()
  }

  private fun readProfile() {
    _state.value = EventsTabState.LOADING
    val user = auth.currentUser() ?: return run { _state.value = EventsTabState.STUDENT }
    read?.cancel()
    read = viewModelScope.launch {
      _state.value =
          when (val result = profiles.getProfile(user.uid)) {
            is ProfileResult.Found ->
                if (result.profile.isVerifiedAssociation) EventsTabState.ASSOCIATION
                else EventsTabState.STUDENT
            ProfileResult.NotFound -> EventsTabState.STUDENT
            ProfileResult.NetworkError,
            ProfileResult.UnexpectedError -> EventsTabState.ERROR
          }
    }
  }
}
