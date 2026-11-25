// Contributors: Claude (Opus 5.5, Anthropic) (drafted Add by @username, #452); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.UsernameLookupResult
import com.polysocial.model.user.UsernameRepository
import com.polysocial.model.user.normalizeUsername
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a lookup opened no card (Figma section 18, #480's frames 06, 07 and 09). */
enum class UsernameLookupProblem {
  /** Nobody holds the name, it can't be a username, or its student has no public profile. */
  NOT_FOUND,

  /** The name is the signed-in student's own. */
  OWN_NAME,

  /** The name or the profile couldn't be read: offline, or another failure. */
  FAILED,
}

/**
 * What Add by @username shows: the name as typed in [input], whether a lookup is [looking], why the
 * last one opened no card ([problem]), and the found student's [card], which covers the Friends
 * screen while it is shown.
 */
data class AddByUsernameUiState(
    val input: String = "",
    val looking: Boolean = false,
    val problem: UsernameLookupProblem? = null,
    val card: FriendCard? = null,
)

/**
 * Add a friend by username (#452), on Friends. A submitted name is cleaned ([normalizeUsername])
 * and looked up once, from the server ([UsernameRepository.lookUp]): never as the student types, so
 * no list of students can be built. A name someone else holds opens their public profile's card,
 * the one Scan a friend's code shows, and the request is sent only from that card: a deleted
 * account's name is free at once and someone else may hold it, so the student checks who it is
 * first (Sprint 6 PO, #445). The card's button is the shared [FriendCardController].
 *
 * The student's own name is recognised by its UID, before any profile is read. Editing the name
 * clears the last message and drops the answer of a lookup still running; only the latest submitted
 * lookup's answer is shown, even when an older one of the same name answers later.
 */
@HiltViewModel
class AddByUsernameViewModel
@Inject
constructor(
    auth: AuthRepository,
    private val usernames: UsernameRepository,
    private val profiles: PublicProfileRepository,
    friends: FriendRepository,
) : ViewModel() {
  private val myUid: String? = auth.currentUser()?.uid
  private val state = MutableStateFlow(AddByUsernameUiState())
  val uiState: StateFlow<AddByUsernameUiState> = state.asStateFlow()

  private val cards =
      FriendCardController(
          myUid,
          friends,
          viewModelScope,
          state,
          cardOf = { it.card },
          withCard = { current, card -> current.copy(card = card) },
      )

  /**
   * The name as the student types it, without a leading `@`: the field already shows one, so a
   * pasted `@nora_meier` doesn't show two. The last message no longer applies to it.
   */
  fun onInput(text: String) {
    state.update { it.copy(input = text.removePrefix("@"), looking = false, problem = null) }
  }

  /** How many lookups were submitted: only the latest one's answer is shown. */
  private var lookups = 0

  /** Look up (or Try again): one lookup of the typed name, unless one is already running. */
  fun onLookUp() {
    val current = state.value
    val name = normalizeUsername(current.input)
    if (current.looking || name.isEmpty()) return
    state.value = current.copy(looking = true, problem = null)
    val lookup = ++lookups
    viewModelScope.launch {
      val found = find(name)
      state.update {
        // The student edited the name meanwhile, or submitted it again (even unchanged): this
        // answer is no longer the one the screen waits for.
        if (!it.looking || lookup != lookups) return@update it
        when (found) {
          is Found.Card -> it.copy(looking = false, card = cards.cardFor(found.profile))
          is Found.Problem -> it.copy(looking = false, problem = found.problem)
        }
      }
    }
  }

  /** The card's button: Add friend sends a request, Accept request accepts theirs. */
  fun onCardButton() = cards.onButton()

  /** Closes the card; the name stays in the field. */
  fun onCloseCard() {
    state.update { it.copy(card = null) }
  }

  private sealed interface Found {
    data class Card(val profile: PublicProfile) : Found

    data class Problem(val problem: UsernameLookupProblem) : Found
  }

  /** Who holds [name], then their public profile, read by UID like after a scan. */
  private suspend fun find(name: String): Found {
    val uid =
        when (val result = usernames.lookUp(name)) {
          is UsernameLookupResult.Found -> result.uid
          UsernameLookupResult.NotFound -> return Found.Problem(UsernameLookupProblem.NOT_FOUND)
          UsernameLookupResult.NetworkError,
          UsernameLookupResult.UnexpectedError -> return Found.Problem(UsernameLookupProblem.FAILED)
        }
    if (uid == myUid) return Found.Problem(UsernameLookupProblem.OWN_NAME)
    return when (val result = profiles.getPublicProfile(uid)) {
      is PublicProfileResult.Found -> Found.Card(result.profile)
      PublicProfileResult.NotFound -> Found.Problem(UsernameLookupProblem.NOT_FOUND)
      PublicProfileResult.NetworkError,
      PublicProfileResult.UnexpectedError -> Found.Problem(UsernameLookupProblem.FAILED)
    }
  }
}
