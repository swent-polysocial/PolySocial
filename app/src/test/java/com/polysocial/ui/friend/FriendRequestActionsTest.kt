// Contributors: OpenAI Codex (6.2 Sol, high; friend request action and race tests, #361).
package com.polysocial.ui.friend

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.friendshipId
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FriendRequestActionsTest {
  @get:Rule val main = MainDispatcherRule()

  private val auth = FakeAuthRepository(AuthUser("me", "student@epfl.ch", true))
  private val friends = FakeFriendRepository(currentUid = "me")
  private val network = FakeNetworkMonitor()
  private val incoming = request("incoming", "incoming")
  private val sent = request("sent", "me")

  private fun create(repository: FriendRepository = friends): FriendsViewModel {
    friends.seedFriendship(incoming)
    friends.seedFriendship(sent)
    return FriendsViewModel(auth, repository, FakePublicProfileRepository(), network)
  }

  private fun FriendsViewModel.rows() = (uiState.value.content as FriendsContent.ListContent).rows

  private fun FriendsViewModel.row(friendship: Friendship) =
      rows().single { it.friendship.id == friendship.id }

  @Test
  fun acceptMovesIncomingToFriendsAndASecondActionCannotRunWhileWaiting() = runTest {
    val vm = create()
    runCurrent()
    val displayed = vm.row(incoming)
    val response = CompletableDeferred<Unit>()
    friends.acceptResponseGate = response
    vm.answerRequest(displayed, FriendListAction.ACCEPT)
    vm.answerRequest(displayed, FriendListAction.ACCEPT)
    vm.answerRequest(vm.row(sent), FriendListAction.CANCEL)
    runCurrent()

    assertTrue(vm.uiState.value.writing)
    assertEquals(FriendshipStatus.ACCEPTED, friends.friendship(incoming.id)?.status)
    assertEquals(sent, friends.friendship(sent.id))
    assertEquals(FriendsSection.FRIENDS, vm.row(incoming).section)
    response.complete(Unit)
    runCurrent()
    assertFalse(vm.uiState.value.writing)
    assertNull(vm.uiState.value.notice)
  }

  @Test
  fun acceptingTheLastCachedRequestRecoversWhenTheDelayedListenerCatchesUp() = runTest {
    friends.readFromCache = true
    val repository =
        object : FriendRepository by friends {
          override suspend fun accept(shown: Friendship) = FriendshipActionResult.Done
        }
    val vm = create(repository)
    friends.deleteFriendship(sent.id)
    runCurrent()
    vm.answerRequest(vm.row(incoming), FriendListAction.ACCEPT)
    runCurrent()
    assertEquals(FriendsContent.Loading, vm.uiState.value.content)

    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(FriendsContent.Error, vm.uiState.value.content)
    vm.retryLoad()
    friends.seedFriendship(
        incoming.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = Instant.EPOCH)
    )
    friends.readFromCache = false
    runCurrent()

    assertEquals(FriendsSection.FRIENDS, vm.rows().single().section)
    assertNull(vm.uiState.value.notice)
  }

  @Test
  fun declineAndCancelDeleteAndTheseStudentsMayRequestAgain() = runTest {
    val vm = create()
    runCurrent()
    vm.answerRequest(vm.row(incoming), FriendListAction.DECLINE)
    runCurrent()
    assertNull(friends.friendship(incoming.id))
    assertEquals(listOf(sent), vm.rows().map { it.friendship })

    vm.answerRequest(vm.row(sent), FriendListAction.CANCEL)
    runCurrent()
    assertNull(friends.friendship(sent.id))
    assertTrue(vm.rows().isEmpty())
    assertNull(vm.uiState.value.notice)

    friends.sendRequest("sent")
    runCurrent()
    assertEquals(FriendsSection.SENT, vm.rows().single().section)
  }

  @Test
  fun offlineAttemptsForEveryActionAreRefusedAndCanBeRetried() = runTest {
    for (action in
        listOf(FriendListAction.ACCEPT, FriendListAction.DECLINE, FriendListAction.CANCEL)) {
      val vmStore = androidx.lifecycle.ViewModelStore()
      val vm = create()
      vmStore.put(action.name, vm)
      runCurrent()
      val target = if (action == FriendListAction.CANCEL) sent else incoming
      val row = vm.row(target)

      network.online = false
      friends.acceptFailure = FriendshipActionResult.NetworkError
      friends.removeFailure = FriendshipActionResult.NetworkError
      vm.answerRequest(row, action)
      runCurrent()
      assertEquals(FriendsNotice.OFFLINE, vm.uiState.value.notice)
      assertEquals(target, friends.friendship(target.id))
      vm.retryAction()
      runCurrent()
      assertEquals(FriendsNotice.OFFLINE, vm.uiState.value.notice)

      network.online = true
      friends.acceptFailure = null
      friends.removeFailure = null
      vm.retryAction()
      runCurrent()
      if (action == FriendListAction.ACCEPT) {
        assertEquals(FriendsSection.FRIENDS, vm.row(target).section)
      } else {
        assertNull(friends.friendship(target.id))
        assertFalse(vm.rows().any { it.friendship.id == target.id })
      }
      assertNull(vm.uiState.value.notice)
      vmStore.clear()
    }
  }

  @Test
  fun everyRepositoryFailureHasRetryAndKeepsTheApplicableRow() = runTest {
    val vm = create()
    runCurrent()
    for ((target, action) in
        listOf(
            incoming to FriendListAction.ACCEPT,
            incoming to FriendListAction.DECLINE,
            sent to FriendListAction.CANCEL,
        )) {
      for ((result, expected) in
          listOf(
              FriendshipActionResult.NetworkError to FriendsNotice.OFFLINE,
              FriendshipActionResult.UnexpectedError to FriendsNotice.FAILED,
          )) {
        friends.acceptFailure = result
        friends.removeFailure = result
        vm.answerRequest(vm.row(target), action)
        runCurrent()
        assertEquals(expected, vm.uiState.value.notice)
        assertEquals(action, vm.uiState.value.noticeAction)
        assertEquals(target, vm.row(target).friendship)
        vm.retryAction()
        runCurrent()
        assertEquals(expected, vm.uiState.value.notice)
        assertEquals(action, vm.uiState.value.noticeAction)
        vm.dismissNotice()
        assertNull(vm.uiState.value.notice)
        assertNull(vm.uiState.value.noticeAction)
      }
    }
  }

  @Test
  fun staleCancelCannotRemoveAnAcceptedFriendshipEvenBeforeTheListenerCatchesUp() = runTest {
    val actual =
        sent.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = Instant.EPOCH.plusSeconds(1))
    val repository =
        object : FriendRepository by friends {
          override suspend fun remove(shown: Friendship): FriendshipActionResult {
            friends.seedFriendship(actual)
            return friends.remove(shown)
          }
        }
    val vm = create(repository)
    runCurrent()
    vm.answerRequest(vm.row(sent), FriendListAction.CANCEL)
    runCurrent()

    assertEquals(FriendsNotice.FRIENDSHIP_CHANGED, vm.uiState.value.notice)
    assertEquals(actual, vm.row(sent).friendship)
    assertEquals(FriendsSection.FRIENDS, vm.row(sent).section)
    vm.retryAction()
    runCurrent()
    assertEquals(actual, friends.friendship(sent.id))
  }

  @Test
  fun aGoneRequestDisappearsBeforeTheListenerAndAReplacementKeepsItsActions() = runTest {
    friends.acceptFailure = FriendshipActionResult.Gone
    val vm = create()
    runCurrent()
    vm.answerRequest(vm.row(incoming), FriendListAction.ACCEPT)
    runCurrent()
    assertEquals(FriendsNotice.FRIENDSHIP_CHANGED, vm.uiState.value.notice)
    assertEquals(listOf(sent), vm.rows().map { it.friendship })

    val replacement = incoming.copy(createdAt = Instant.EPOCH.plusSeconds(5))
    friends.seedFriendship(replacement)
    friends.acceptFailure = null
    runCurrent()
    vm.answerRequest(vm.row(replacement), FriendListAction.ACCEPT)
    runCurrent()
    assertEquals(FriendsSection.FRIENDS, vm.row(replacement).section)
  }

  @Test
  fun aPreviouslyFailedRequestThatVanishesCannotBeRetried() = runTest {
    val vm = create()
    runCurrent()
    friends.removeFailure = FriendshipActionResult.UnexpectedError
    vm.answerRequest(vm.row(incoming), FriendListAction.DECLINE)
    runCurrent()
    friends.deleteFriendship(incoming.id)
    runCurrent()
    vm.retryAction()
    runCurrent()

    assertEquals(FriendsNotice.FRIENDSHIP_CHANGED, vm.uiState.value.notice)
    assertEquals(listOf(sent), vm.rows().map { it.friendship })
  }

  @Test
  fun answerRejectsActionsFromTheWrongSectionAndCannotRemoveAFriend() = runTest {
    val vm = create()
    runCurrent()
    vm.answerRequest(vm.row(sent), FriendListAction.ACCEPT)
    vm.answerRequest(vm.row(sent), FriendListAction.DECLINE)
    vm.answerRequest(vm.row(incoming), FriendListAction.CANCEL)
    vm.answerRequest(vm.row(incoming), FriendListAction.REMOVE)
    runCurrent()
    assertNull(vm.uiState.value.notice)
    assertEquals(setOf(sent, incoming), vm.rows().map { it.friendship }.toSet())
  }

  private fun request(peer: String, sender: String) =
      Friendship(
          checkNotNull(friendshipId("me", peer)),
          listOf("me", peer).sorted(),
          sender,
          FriendshipStatus.PENDING,
          Instant.EPOCH,
          null,
      )
}
