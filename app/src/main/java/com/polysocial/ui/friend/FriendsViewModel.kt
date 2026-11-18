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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

internal data class FriendsUiState(
    val content: FriendsContent = FriendsContent.Loading,
    val removal: FriendRow? = null,
    val writing: Boolean = false,
    val notice: FriendsNotice? = null,
)

/**
 * Observes the student's friendships once per load and looks up each peer independently. List
 * updates are immediate, even when a name can't be loaded; successful cached names stay usable
 * across a connection change. A removal always targets the version shown at confirmation.
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
  private var retryTarget: Friendship? = null

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
    clearProfileReads()
    readFailed = false
    listenerEnded = false
    render()
    if (me == null) {
      readFailed = true
      render()
      return
    }
    timeout = viewModelScope.launch {
      delay(FRIENDS_TIMEOUT_MILLIS)
      readFailed = true
      render()
    }
    listener = viewModelScope.launch {
      friends.observeFriendships().collect { result ->
        when (result) {
          FriendshipsResult.Error -> {
            timeout?.cancel()
            readFailed = true
            listenerEnded = true
          }
          is FriendshipsResult.Success -> {
            snapshot = result
            val savedOrConfirmed = result.friendships.isNotEmpty() || !result.fromCache
            if (savedOrConfirmed) {
              timeout?.cancel()
              readFailed = false
            }
            hidden.entries.removeAll { (_, shown) -> shown !in result.friendships }
            readMissingProfiles()
          }
        }
        render()
      }
    }
  }

  private fun clearProfileReads() {
    profileReads.values.forEach { it.cancel() }
    profileReads.clear()
  }

  private fun readMissingProfiles() {
    val uid = me ?: return
    val peers = snapshot?.friendships.orEmpty().mapNotNull { it.otherUid(uid) }.toSet()
    (profileReads.keys - peers).forEach { profileReads.remove(it)?.cancel() }
    profiles.keys.retainAll(peers)
    for (peer in peers - profileReads.keys - profiles.keys) {
      profileReads[peer] = viewModelScope.launch {
        val result =
            if (online) {
              withTimeoutOrNull(FRIENDS_TIMEOUT_MILLIS) { publicProfiles.getPublicProfile(peer) }
                  ?: publicProfiles.getCachedPublicProfile(peer)
            } else {
              publicProfiles.getCachedPublicProfile(peer)
            }
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

  private fun render() {
    val rows = rows()
    val content =
        when {
          !online && rows.isNotEmpty() -> FriendsContent.ListContent(rows, offline = true)
          !online && (snapshot != null || readFailed) -> FriendsContent.OfflineWithoutSavedData
          listenerEnded || readFailed -> FriendsContent.Error
          rows.isNotEmpty() || snapshot?.fromCache == false ->
              FriendsContent.ListContent(rows, offline = false)
          else -> FriendsContent.Loading
        }
    state.value = state.value.copy(content = content)
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
    remove(target)
  }

  fun retryAction() {
    retryTarget?.let(::remove)
  }

  fun dismissNotice() {
    retryTarget = null
    state.value = state.value.copy(notice = null)
  }

  private fun isCurrent(shown: Friendship): Boolean =
      shown in snapshot?.friendships.orEmpty() && hidden[shown.id] != shown

  private fun remove(shown: Friendship) {
    if (state.value.writing) return
    dismissNotice()
    if (!isCurrent(shown)) {
      state.value = state.value.copy(notice = FriendsNotice.FRIENDSHIP_CHANGED)
      return
    }
    state.value = state.value.copy(writing = true)
    viewModelScope.launch {
      val result =
          if (network.isOnline()) friends.remove(shown) else FriendshipActionResult.NetworkError
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
        retryTarget = shown
      }
      state.value = state.value.copy(writing = false, notice = notice)
    }
  }
}

private const val FRIENDS_TIMEOUT_MILLIS = 5_000L
