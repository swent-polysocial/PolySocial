// Contributors: OpenAI Codex (6.2 Sol, high; accept, decline and cancel requests, #361).
// Contributors: OpenAI Codex (6.2 Sol, high; friends list state, public profile reads and removal,
// #360).
package com.polysocial.ui.friend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.otherUid
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.getPublicProfileWithCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class FriendsSection {
  INCOMING,
  SENT,
  FRIENDS,
}

internal data class FriendRow(
    val friendship: Friendship,
    val uid: String,
    val section: FriendsSection,
    val profile: PublicProfile?,
)

internal sealed interface FriendsContent {
  data object Loading : FriendsContent

  data object Error : FriendsContent

  data object OfflineWithoutSavedData : FriendsContent

  data class ListContent(val rows: List<FriendRow>, val offline: Boolean) : FriendsContent
}

internal enum class FriendsNotice {
  OFFLINE,
  FAILED,
  FRIENDSHIP_CHANGED,
}

internal enum class FriendListAction(val section: FriendsSection) {
  REMOVE(FriendsSection.FRIENDS),
  ACCEPT(FriendsSection.INCOMING),
  DECLINE(FriendsSection.INCOMING),
  CANCEL(FriendsSection.SENT),
}

private data class FriendshipChange(val action: FriendListAction, val shown: Friendship)

internal data class FriendsUiState(
    val content: FriendsContent = FriendsContent.Loading,
    val removal: FriendRow? = null,
    val writing: Boolean = false,
    val notice: FriendsNotice? = null,
    val noticeAction: FriendListAction? = null,
)

/**
 * Observes the student's friendships once per load and looks up each peer independently. List
 * updates are immediate, even when a name can't be loaded; successful cached names stay usable
 * across a connection change. Names for peers still on the list are kept until the screen is
 * reopened. Each action targets the version displayed when tapped or confirmed, including on retry.
 */
@HiltViewModel
class FriendsViewModel
@Inject
constructor(
    auth: AuthRepository,
    private val friends: FriendRepository,
    private val publicProfiles: PublicProfileRepository,
    private val network: NetworkMonitor,
) : ViewModel() {
  private val me = auth.currentUser()?.uid
  private val state = MutableStateFlow(FriendsUiState())
  internal val uiState = state.asStateFlow()

  private var online = network.isOnline()
  private var snapshot: FriendshipsResult.Success? = null
  private var readFailed = false
  private var listenerEnded = false
  private var listener: Job? = null
  private var timeout: Job? = null
  private val profiles = mutableMapOf<String, PublicProfile>()
  private val profileReads = mutableMapOf<String, Job>()
  private val hidden = mutableMapOf<String, Friendship>()
  private var retryTarget: FriendshipChange? = null

  init {
    observeFriendships()
    viewModelScope.launch {
      network.observeOnline().collect { connected ->
        if (connected != online) {
          online = connected
          if (connected) {
            clearProfileReads()
            if (listenerEnded) observeFriendships() else readMissingProfiles()
          }
          render()
        }
      }
    }
  }

  fun retryLoad() {
    when (state.value.content) {
      FriendsContent.Error,
      FriendsContent.OfflineWithoutSavedData -> observeFriendships()
      else -> Unit
    }
  }

  private fun observeFriendships() {
    listener?.cancel()
    timeout?.cancel()
    timeout = null
    clearProfileReads()
    readFailed = me == null
    listenerEnded = false
    render()
    if (me == null) return
    listener = viewModelScope.launch {
      friends.observeFriendships().collect { result ->
        when (result) {
          FriendshipsResult.Error -> {
            readFailed = true
            listenerEnded = true
          }
          is FriendshipsResult.Success -> {
            snapshot = result
            hidden.entries.removeAll { (_, shown) -> shown !in result.friendships }
            readMissingProfiles(retryFailed = !result.fromCache)
          }
        }
        render(receivedSnapshot = result is FriendshipsResult.Success)
      }
    }
  }

  private fun clearProfileReads() {
    profileReads.values.forEach { it.cancel() }
    profileReads.clear()
  }

  private fun readMissingProfiles(retryFailed: Boolean = false) {
    val uid = me ?: return
    val peers = snapshot?.friendships.orEmpty().mapNotNull { it.otherUid(uid) }.toSet()
    (profileReads.keys - peers).forEach { profileReads.remove(it)?.cancel() }
    profiles.keys.retainAll(peers)
    if (retryFailed) profileReads.values.removeAll { it.isCompleted }
    for (peer in peers - profileReads.keys - profiles.keys) {
      profileReads[peer] = viewModelScope.launch {
        val result = publicProfiles.getPublicProfileWithCache(peer, FRIENDS_TIMEOUT_MILLIS, online)
        if (result is PublicProfileResult.Found) {
          profiles[peer] = result.profile
          render()
        }
      }
    }
  }

  private fun rows(): List<FriendRow> {
    val uid = me ?: return emptyList()
    return snapshot
        ?.friendships
        .orEmpty()
        .filterNot { it == hidden[it.id] }
        .sortedWith(compareByDescending<Friendship> { it.createdAt }.thenBy { it.id })
        .mapNotNull { friendship ->
          val peer = friendship.otherUid(uid) ?: return@mapNotNull null
          val section =
              when {
                friendship.status == FriendshipStatus.ACCEPTED -> FriendsSection.FRIENDS
                friendship.requestedBy == uid -> FriendsSection.SENT
                else -> FriendsSection.INCOMING
              }
          FriendRow(friendship, peer, section, profiles[peer])
        }
  }

  private fun render(receivedSnapshot: Boolean = false) {
    val rows = rows()
    val usable = rows.isNotEmpty() || snapshot?.fromCache == false
    if (receivedSnapshot && usable) readFailed = false
    val content = friendsContent(online, rows, snapshot, readFailed, listenerEnded)
    state.value = state.value.copy(content = content)
    if (content == FriendsContent.Loading) {
      // A cached list can become unresolved again when its last shown row disappears.
      if (timeout == null) {
        timeout = viewModelScope.launch {
          delay(FRIENDS_TIMEOUT_MILLIS)
          readFailed = true
          render()
        }
      }
    } else {
      timeout?.cancel()
      timeout = null
    }
  }

  internal fun requestRemoval(row: FriendRow) {
    if (state.value.writing || row.section != FriendsSection.FRIENDS) return
    if (!isCurrent(row.friendship)) return
    state.value = state.value.copy(removal = row)
  }

  fun keepFriend() {
    state.value = state.value.copy(removal = null)
  }

  fun confirmRemoval() {
    val target = state.value.removal?.friendship ?: return
    keepFriend()
    submit(FriendshipChange(FriendListAction.REMOVE, target))
  }

  /**
   * Runs an action from the applicable request section. Removing a friend requires confirmation.
   */
  internal fun answerRequest(row: FriendRow, action: FriendListAction) {
    if (action == FriendListAction.REMOVE || row.section != action.section) return
    submit(FriendshipChange(action, row.friendship))
  }

  fun retryAction() {
    retryTarget?.let(::submit)
  }

  fun dismissNotice() {
    retryTarget = null
    state.value = state.value.copy(notice = null, noticeAction = null)
  }

  private fun isCurrent(shown: Friendship): Boolean =
      shown in snapshot?.friendships.orEmpty() && hidden[shown.id] != shown

  private fun submit(change: FriendshipChange) {
    val shown = change.shown
    if (state.value.writing) return
    dismissNotice()
    if (!isCurrent(shown)) {
      state.value =
          state.value.copy(notice = FriendsNotice.FRIENDSHIP_CHANGED, noticeAction = change.action)
      return
    }
    state.value = state.value.copy(writing = true)
    viewModelScope.launch {
      val result =
          if (change.action == FriendListAction.ACCEPT) friends.accept(shown)
          else friends.remove(shown)
      val notice =
          when (result) {
            FriendshipActionResult.Done -> null
            FriendshipActionResult.Gone -> FriendsNotice.FRIENDSHIP_CHANGED
            FriendshipActionResult.NetworkError -> FriendsNotice.OFFLINE
            FriendshipActionResult.UnexpectedError -> FriendsNotice.FAILED
          }
      if (result == FriendshipActionResult.Done || result == FriendshipActionResult.Gone) {
        // The transaction can answer before the listener. Suppress only the version it acted on.
        if (isCurrent(shown)) hidden[shown.id] = shown
        render()
      } else {
        retryTarget = change
      }
      state.value =
          state.value.copy(
              writing = false,
              notice = notice,
              noticeAction = change.action.takeIf { notice != null },
          )
    }
  }
}

private fun friendsContent(
    online: Boolean,
    rows: List<FriendRow>,
    snapshot: FriendshipsResult.Success?,
    readFailed: Boolean,
    listenerEnded: Boolean,
): FriendsContent =
    when {
      // On a known offline connection, saved rows stay usable.
      !online && rows.isNotEmpty() -> FriendsContent.ListContent(rows, offline = true)
      // With no rows, a snapshot or failure confirms there is nothing to show offline.
      !online && (snapshot != null || readFailed) -> FriendsContent.OfflineWithoutSavedData
      // An online listener failure offers Retry instead of actions based on a stale list.
      listenerEnded || readFailed -> FriendsContent.Error
      // Only the server can confirm that an empty list is current.
      rows.isNotEmpty() || snapshot?.fromCache == false -> FriendsContent.ListContent(rows, false)
      else -> FriendsContent.Loading
    }

private const val FRIENDS_TIMEOUT_MILLIS = 5_000L
