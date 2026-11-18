// Contributors: OpenAI Codex (6.2 Sol, high; friendship list and removal regression tests, #360).
package com.polysocial.ui.friend

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.friendshipId
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.utils.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FriendsViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val auth = FakeAuthRepository(AuthUser("me", "student@epfl.ch", true))
  private val friends = FakeFriendRepository(currentUid = "me")
  private val network = FakeNetworkMonitor()
  private val lea = PublicProfile("lea", "Léa Sample", "IN", "BA3")
  private val emma = PublicProfile("emma", "Emma Sample", "SV", "BA3")
  private val profiles = FakePublicProfileRepository(mutableMapOf("lea" to lea, "emma" to emma))
  private val cachedProfiles =
      object : PublicProfileRepository by profiles {
        override suspend fun getCachedPublicProfile(uid: String) = profiles.getPublicProfile(uid)
      }
  private val accepted = friendship("lea", "me", FriendshipStatus.ACCEPTED)
  private val incoming = friendship("emma", "emma", FriendshipStatus.PENDING)
  private val outgoing = friendship("tom", "me", FriendshipStatus.PENDING)

  private fun create(repository: FriendRepository = friends) =
      FriendsViewModel(auth, repository, cachedProfiles, network)

  private fun FriendsViewModel.list() = uiState.value.content as FriendsContent.ListContent

  @Test
  fun oneListenerSplitsRowsAndKeepsTheMissingProfile() = runTest {
    listOf(accepted, incoming, outgoing).forEach(friends::seedFriendship)
    var subscriptions = 0
    val repository =
        object : FriendRepository by friends {
          override fun observeFriendships(): Flow<FriendshipsResult> = flow {
            subscriptions++
            emitAll(friends.observeFriendships())
          }
        }
    val vm = create(repository)
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)
    runCurrent()

    val rows = vm.list().rows.associateBy { it.section }
    assertEquals(lea, rows.getValue(FriendsSection.FRIENDS).profile)
    assertEquals(emma, rows.getValue(FriendsSection.INCOMING).profile)
    assertEquals("tom", rows.getValue(FriendsSection.SENT).uid)
    assertNull(rows.getValue(FriendsSection.SENT).profile)

    friends.readFromCache = true
    runCurrent()
    assertFalse(vm.list().offline)
    network.online = false
    runCurrent()
    assertTrue(vm.list().offline)
    assertEquals(3, vm.list().rows.size)
    vm.retryLoad()
    runCurrent()
    assertEquals(1, subscriptions)
    assertEquals(3, profiles.requestedUids.size)
  }

  @Test
  fun withoutAStudentTheScreenShowsAnErrorWithoutOpeningAListener() = runTest {
    auth.user = null
    val repository =
        object : FriendRepository by friends {
          override fun observeFriendships(): Flow<FriendshipsResult> =
              error("An unsigned session must not open a listener")
        }
    val vm = create(repository)
    runCurrent()
    assertEquals(FriendsContent.Error, vm.uiState.value.content)
  }

  @Test
  fun aStalledProfileLookupFallsBackToTheCachedName() = runTest {
    friends.seedFriendship(accepted)
    val slowProfiles =
        object : PublicProfileRepository by cachedProfiles {
          override suspend fun getPublicProfile(uid: String): Nothing = awaitCancellation()
        }
    val vm = FriendsViewModel(auth, friends, slowProfiles, network)
    runCurrent()
    assertEquals(accepted, vm.list().rows.single().friendship)
    assertNull(vm.list().rows.single().profile)

    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(lea, vm.list().rows.single().profile)
  }

  @Test
  fun aNetworkErrorFallsBackToTheCachedName() = runTest {
    friends.seedFriendship(accepted)
    val unavailableProfiles =
        object : PublicProfileRepository by cachedProfiles {
          override suspend fun getPublicProfile(uid: String) = PublicProfileResult.NetworkError
        }
    val vm = FriendsViewModel(auth, friends, unavailableProfiles, network)
    runCurrent()

    assertEquals(lea, vm.list().rows.single().profile)
  }

  @Test
  fun aFreshListRetriesMissingNamesWithoutReloadingKnownPeers() = runTest {
    friends.seedFriendship(accepted)
    friends.seedFriendship(outgoing)
    val vm = create()
    runCurrent()
    assertEquals(
        mapOf("lea" to lea, "tom" to null),
        vm.list().rows.associate { it.uid to it.profile },
    )

    val tom = PublicProfile("tom", "Tom Sample", "SC", "BA2")
    profiles.profiles["tom"] = tom
    friends.readFromCache = true
    runCurrent()
    assertNull(vm.list().rows.single { it.uid == "tom" }.profile)

    friends.readFromCache = false
    runCurrent()
    assertEquals(
        mapOf("lea" to lea, "tom" to tom),
        vm.list().rows.associate { it.uid to it.profile },
    )
    assertEquals(1, profiles.requestedUids.count { it == listOf("lea") })
  }

  @Test
  fun reconnectRestartsAFailedListenerAndRefreshesSavedRows() = runTest {
    friends.seedFriendship(accepted)
    val vm = create()
    runCurrent()
    network.online = false
    friends.failListeners()
    runCurrent()
    assertTrue(vm.list().offline)
    assertEquals(accepted, vm.list().rows.single().friendship)

    friends.deleteFriendship(accepted.id)
    network.online = true
    runCurrent()
    assertEquals(FriendsContent.ListContent(emptyList(), false), vm.uiState.value.content)
  }

  @Test
  fun offlineCacheShowsSavedNamesAlongsideAnUncachedPeer() = runTest {
    network.online = false
    friends.readFromCache = true
    friends.seedFriendship(accepted)
    friends.seedFriendship(outgoing)
    val vm = create()
    runCurrent()

    assertTrue(vm.list().offline)
    assertEquals(listOf(lea, null), vm.list().rows.map { it.profile })
    network.online = true
    profiles.profiles["tom"] = PublicProfile("tom", "Tom Sample", "SC", "BA2")
    runCurrent()
    assertFalse(vm.list().offline)
    assertEquals(listOf("Léa Sample", "Tom Sample"), vm.list().rows.map { it.profile?.displayName })
  }

  @Test
  fun emptyCacheOfflineIsDifferentFromServerConfirmedEmpty() = runTest {
    network.online = false
    friends.readFromCache = true
    val vm = create()
    runCurrent()
    assertEquals(FriendsContent.OfflineWithoutSavedData, vm.uiState.value.content)

    network.online = true
    runCurrent()
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)
    friends.readFromCache = false
    runCurrent()
    assertEquals(FriendsContent.ListContent(emptyList(), offline = false), vm.uiState.value.content)
  }

  @Test
  fun offlineRetryReplacesAListenerThatNeverDeliveredCacheData() = runTest {
    network.online = false
    friends.readFromCache = true
    var attempts = 0
    val repository =
        object : FriendRepository by friends {
          override fun observeFriendships(): Flow<FriendshipsResult> = flow {
            if (attempts++ == 0) awaitCancellation()
            emitAll(friends.observeFriendships())
          }
        }
    val vm = create(repository)
    runCurrent()
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)

    advanceTimeBy(2_500)
    network.online = true
    runCurrent()
    network.online = false
    runCurrent()
    advanceTimeBy(2_500)
    runCurrent()
    assertEquals(FriendsContent.OfflineWithoutSavedData, vm.uiState.value.content)

    friends.seedFriendship(accepted)
    vm.retryLoad()
    runCurrent()
    assertEquals(2, attempts)
    assertTrue(vm.list().offline)
    assertEquals(accepted, vm.list().rows.single().friendship)
  }

  @Test
  fun stalledListenerShowsErrorAndRetryCanRecover() = runTest {
    val unblock = CompletableDeferred<Unit>()
    val repository =
        object : FriendRepository by friends {
          override fun observeFriendships(): Flow<FriendshipsResult> = flow {
            unblock.await()
            emitAll(friends.observeFriendships())
          }
        }
    val vm = create(repository)
    advanceTimeBy(4_999)
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(FriendsContent.Error, vm.uiState.value.content)
    vm.retryLoad()
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)
    unblock.complete(Unit)
    runCurrent()
    assertTrue(vm.list().rows.isEmpty())
  }

  @Test
  fun aCacheOnlyEmptySnapshotAfterLoadingStillHasATimeout() = runTest {
    val vm = create()
    runCurrent()
    assertEquals(FriendsContent.ListContent(emptyList(), false), vm.uiState.value.content)

    friends.readFromCache = true
    runCurrent()
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(FriendsContent.Error, vm.uiState.value.content)

    friends.readFromCache = false
    runCurrent()
    assertEquals(FriendsContent.ListContent(emptyList(), false), vm.uiState.value.content)
  }

  @Test
  fun removingTheLastCachedFriendBeforeTheListenerUpdatesStillHasATimeout() = runTest {
    friends.readFromCache = true
    friends.seedFriendship(accepted)
    val repository =
        object : FriendRepository by friends {
          override suspend fun remove(shown: Friendship) = FriendshipActionResult.Done
        }
    val vm = create(repository)
    runCurrent()
    vm.requestRemoval(vm.list().rows.single())
    vm.confirmRemoval()
    runCurrent()
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)

    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(FriendsContent.Error, vm.uiState.value.content)

    friends.deleteFriendship(accepted.id)
    friends.readFromCache = false
    runCurrent()
    assertEquals(FriendsContent.ListContent(emptyList(), false), vm.uiState.value.content)
  }

  @Test
  fun terminalReadFailureShowsRetryAndDoesNotKeepOnlineRows() = runTest {
    friends.seedFriendship(accepted)
    val vm = create()
    runCurrent()
    friends.failListeners()
    runCurrent()
    assertEquals(FriendsContent.Error, vm.uiState.value.content)

    vm.retryLoad()
    runCurrent()
    assertEquals(accepted, vm.list().rows.single().friendship)
  }

  @Test
  fun offlineRemovalCanBeRetriedWithoutQueuingOrRepeatingIt() = runTest {
    friends.seedFriendship(accepted)
    val unblock = CompletableDeferred<Unit>()
    var removals = 0
    val repository =
        object : FriendRepository by friends {
          override suspend fun remove(shown: Friendship): FriendshipActionResult {
            removals++
            unblock.await()
            return friends.remove(shown)
          }
        }
    val vm = create(repository)
    runCurrent()
    val row = vm.list().rows.single()
    vm.requestRemoval(row)
    assertEquals(row, vm.uiState.value.removal)
    vm.keepFriend()
    assertNull(vm.uiState.value.removal)
    assertNotNull(friends.friendship(accepted.id))

    vm.requestRemoval(row)
    network.online = false
    vm.confirmRemoval()
    runCurrent()
    assertEquals(FriendsNotice.OFFLINE, vm.uiState.value.notice)
    assertEquals(0, removals)
    assertEquals(row, vm.list().rows.single())

    network.online = true
    vm.retryAction()
    vm.retryAction()
    vm.confirmRemoval()
    runCurrent()
    assertTrue(vm.uiState.value.writing)
    assertEquals(1, removals)
    unblock.complete(Unit)
    runCurrent()
    assertFalse(vm.uiState.value.writing)
    assertTrue(vm.list().rows.isEmpty())
    assertNull(vm.uiState.value.notice)
  }

  @Test
  fun failedRemovalCanBeDismissedAndAStaleConfirmationKeepsTheReplacement() = runTest {
    friends.seedFriendship(accepted)
    friends.removeFailure = FriendshipActionResult.UnexpectedError
    val vm = create()
    runCurrent()
    val shown = vm.list().rows.single()
    vm.requestRemoval(shown)
    vm.confirmRemoval()
    runCurrent()
    assertEquals(FriendsNotice.FAILED, vm.uiState.value.notice)
    vm.dismissNotice()
    assertNull(vm.uiState.value.notice)

    vm.requestRemoval(shown)
    val replacement = accepted.copy(createdAt = accepted.createdAt.plusSeconds(1))
    friends.seedFriendship(replacement)
    friends.removeFailure = null
    runCurrent()
    vm.confirmRemoval()
    runCurrent()
    assertEquals(FriendsNotice.FRIENDSHIP_CHANGED, vm.uiState.value.notice)
    assertEquals(replacement, friends.friendship(accepted.id))
  }

  @Test
  fun lateRemovalReplyKeepsTheReplacementFriendshipAndRejectsStaleActions() = runTest {
    friends.seedFriendship(accepted)
    friends.seedFriendship(incoming)
    val reply = CompletableDeferred<Unit>()
    val repository =
        object : FriendRepository by friends {
          override suspend fun remove(shown: Friendship): FriendshipActionResult {
            val result = friends.remove(shown)
            reply.await()
            return result
          }
        }
    val vm = create(repository)
    runCurrent()
    val shown = vm.list().rows.single { it.uid == "lea" }
    vm.requestRemoval(vm.list().rows.single { it.uid == "emma" })
    assertNull(vm.uiState.value.removal)

    vm.requestRemoval(shown)
    vm.confirmRemoval()
    runCurrent()
    assertTrue(vm.uiState.value.writing)
    assertEquals(listOf(incoming), vm.list().rows.map { it.friendship })

    val replacement = accepted.copy(createdAt = accepted.createdAt.plusSeconds(1))
    friends.seedFriendship(replacement)
    runCurrent()
    vm.requestRemoval(vm.list().rows.single { it.uid == "lea" })
    assertNull(vm.uiState.value.removal)

    reply.complete(Unit)
    runCurrent()
    vm.requestRemoval(shown)
    assertNull(vm.uiState.value.removal)
    assertFalse(vm.uiState.value.writing)
    assertEquals(replacement, vm.list().rows.single { it.uid == "lea" }.friendship)
    assertEquals(replacement, friends.friendship(accepted.id))
  }

  @Test
  fun goneResultHidesOnlyTheVersionShownWithoutWaitingForTheListener() = runTest {
    friends.seedFriendship(accepted)
    friends.removeFailure = FriendshipActionResult.Gone
    val vm = create()
    runCurrent()
    vm.requestRemoval(vm.list().rows.single())
    vm.confirmRemoval()
    runCurrent()
    assertEquals(FriendsNotice.FRIENDSHIP_CHANGED, vm.uiState.value.notice)
    assertTrue(vm.list().rows.isEmpty())

    friends.seedFriendship(incoming)
    runCurrent()
    assertEquals(listOf(incoming), vm.list().rows.map { it.friendship })

    val replacement = accepted.copy(createdAt = accepted.createdAt.plusSeconds(1))
    friends.seedFriendship(replacement)
    runCurrent()
    assertEquals(replacement, vm.list().rows.single { it.uid == "lea" }.friendship)
  }

  private fun friendship(peer: String, sender: String, status: FriendshipStatus) =
      Friendship(
          id = checkNotNull(friendshipId("me", peer)),
          uids = listOf("me", peer).sorted(),
          requestedBy = sender,
          status = status,
          createdAt = Instant.EPOCH,
          acceptedAt = Instant.EPOCH.takeIf { status == FriendshipStatus.ACCEPTED },
      )
}
