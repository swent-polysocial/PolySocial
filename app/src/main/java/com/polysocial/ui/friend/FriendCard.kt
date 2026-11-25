// Contributors: Claude (Opus 5.5, Anthropic) (moved the friend button and its request logic out of
// Scan a friend's code to share it, and made a late response apply only to the card that tapped,
// #452; the logic itself is #359's); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.SendFriendRequestResult
import com.polysocial.model.user.PublicProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a student's card offers, from the live friendship with them (#357). */
enum class FriendButton {
  /** No friendship yet: "Add friend" sends a request. */
  ADD,

  /** The student sent them a request: "Request sent", nothing to tap. */
  SENT,

  /** They sent the student a request: "Accept request". */
  ACCEPT,

  /** They are friends: "Friends", nothing to tap. */
  FRIENDS,
}

/** Why the card's button didn't work. Nothing was written or queued in either case. */
enum class FriendActionError {
  OFFLINE,
  FAILED,
}

/**
 * Another student's card (Figma "Scanned student · Public profile"): their public [profile] and the
 * [button] the live friendship with them gives. [busy] while a tap's request runs; [error] after
 * one failed.
 */
data class FriendCard(
    val profile: PublicProfile,
    val button: FriendButton,
    val busy: Boolean = false,
    val error: FriendActionError? = null,
)

/**
 * The friend button of a student's card, shared by every screen that shows one: Scan a friend's
 * code (#359) and Add by username (#452). It follows the signed-in student's friendships as
 * `observeFriendships` reports them (#357), so the button changes by itself when the other student
 * accepts or sends a request, and [onButton] sends a request or accepts theirs. A failure is shown
 * on the card and nothing is queued, so tapping again tries again.
 *
 * The card lives in the host ViewModel's own [state], so the screen keeps one UI state that changes
 * at once: [cardOf] reads the card that [state] shows, if any, and [withCard] puts a changed card
 * back. It starts listening when created and stops with [scope].
 *
 * @param myUid the signed-in student, or null when nobody is signed in.
 */
class FriendCardController<S>(
    private val myUid: String?,
    private val friends: FriendRepository,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<S>,
    private val cardOf: (S) -> FriendCard?,
    private val withCard: (S, FriendCard) -> S,
) {
  /** The student's friendships, as last delivered by the listener; empty until it delivers. */
  private var friendships: List<Friendship> = emptyList()

  /**
   * How many times the listener changed the friendship with the student on the card. A tap's
   * response that arrives after such a change is older than what the listener shows, so the
   * listener's button wins over it.
   */
  private var cardFriendshipChanges = 0

  /** How many taps sent a request: only the latest one's response can change the card. */
  private var taps = 0

  /** True once the listener ended on [FriendshipsResult.Error]: it shows no change after that. */
  private var listenerStopped = false

  init {
    listen()
  }

  /** The card of [profile], with the button the friendships known so far give. */
  fun cardFor(profile: PublicProfile): FriendCard = FriendCard(profile, buttonFor(profile.uid))

  /**
   * Follows the student's friendships until the listener ends on an error. A failed listener keeps
   * the last known friendships, so Add friend still works; a response it can't explain then says it
   * failed and calls this again (see [act]).
   */
  private fun listen() {
    listenerStopped = false
    scope.launch {
      friends.observeFriendships().collect { result ->
        when (result) {
          is FriendshipsResult.Success -> onFriendships(result.friendships)
          FriendshipsResult.Error -> listenerStopped = true
        }
      }
    }
  }

  /**
   * Shows the card's button from [latest]. When the friendship with the card's student changed, an
   * earlier tap's error is cleared too: it was about a state that no longer holds.
   */
  private fun onFriendships(latest: List<Friendship>) {
    val cardUid = cardOf(state.value)?.profile?.uid
    val before = cardUid?.let(::friendshipWith)
    friendships = latest
    val changed = cardUid != null && friendshipWith(cardUid) != before
    if (changed) cardFriendshipChanges++
    state.update {
      val card = cardOf(it) ?: return@update it
      withCard(
          it,
          card.copy(
              button = buttonFor(card.profile.uid),
              error = if (changed) null else card.error,
          ),
      )
    }
  }

  /** The card's button: Add friend sends a request, Accept request accepts theirs. */
  fun onButton() {
    val card = cardOf(state.value) ?: return
    if (card.busy) return
    val uid = card.profile.uid
    when (card.button) {
      FriendButton.ADD -> act(card) { sent(friends.sendRequest(uid)) }
      FriendButton.ACCEPT -> {
        val request = friendshipWith(uid) ?: return
        act(card) { accepted(friends.accept(request)) }
      }
      FriendButton.SENT,
      FriendButton.FRIENDS -> Unit
    }
  }

  /**
   * Runs [call] with the card busy, then shows the button and error it returns, only on the card
   * that tapped: still shown and busy, and not tapped again since. A card closed meanwhile (and
   * reopened, which is a new card) shows the listener's button instead, which may be newer than the
   * response. The button it proves is kept only if the listener changed nothing meanwhile:
   * otherwise the listener's is newer (accepted or declined before the response came).
   *
   * A response that leaves the current state to the listener ([Outcome.fromListener]) can't be
   * shown while the listener is stopped: it says it failed, and listening starts again, so the
   * listener's next delivery shows the current button (or tapping again retries).
   */
  private fun act(card: FriendCard, call: suspend () -> Outcome) {
    state.update { withCard(it, card.copy(busy = true, error = null)) }
    val changesAtTap = cardFriendshipChanges
    val tap = ++taps
    scope.launch {
      val outcome = call()
      val unseen = outcome.fromListener && listenerStopped
      state.update {
        val current = cardOf(it)
        if (current == null || !current.busy || current.profile.uid != card.profile.uid) {
          return@update it
        }
        if (tap != taps) return@update it
        val proven = outcome.button.takeIf { cardFriendshipChanges == changesAtTap }
        withCard(
            it,
            current.copy(
                busy = false,
                button = proven ?: buttonFor(current.profile.uid),
                error = if (unseen) FriendActionError.FAILED else outcome.error,
            ),
        )
      }
      if (unseen) listen()
    }
  }

  /**
   * A tap's result: the button it proves (null to keep the listener's) and its error.
   * [fromListener] when the call changed nothing and only the listener can show the current state
   * (`AlreadyExists`, `Gone`).
   */
  private data class Outcome(
      val button: FriendButton?,
      val error: FriendActionError? = null,
      val fromListener: Boolean = false,
  )

  private fun sent(result: SendFriendRequestResult) =
      when (result) {
        // Shown at once: the listener confirms it a moment later.
        SendFriendRequestResult.Sent -> Outcome(FriendButton.SENT)
        SendFriendRequestResult.AlreadyExists -> Outcome(null, fromListener = true)
        SendFriendRequestResult.NetworkError -> Outcome(null, FriendActionError.OFFLINE)
        SendFriendRequestResult.UnexpectedError -> Outcome(null, FriendActionError.FAILED)
      }

  private fun accepted(result: FriendshipActionResult) =
      when (result) {
        FriendshipActionResult.Done -> Outcome(FriendButton.FRIENDS)
        // Cancelled or changed since: the listener shows what it is now.
        FriendshipActionResult.Gone -> Outcome(null, fromListener = true)
        FriendshipActionResult.NetworkError -> Outcome(null, FriendActionError.OFFLINE)
        FriendshipActionResult.UnexpectedError -> Outcome(null, FriendActionError.FAILED)
      }

  private fun friendshipWith(uid: String): Friendship? = friendships.firstOrNull {
    uid in it.uids && myUid in it.uids
  }

  private fun buttonFor(uid: String): FriendButton {
    val friendship = friendshipWith(uid) ?: return FriendButton.ADD
    return when {
      friendship.status == FriendshipStatus.ACCEPTED -> FriendButton.FRIENDS
      friendship.requestedBy == myUid -> FriendButton.SENT
      else -> FriendButton.ACCEPT
    }
  }
}
