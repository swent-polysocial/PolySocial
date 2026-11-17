// Contributors: Claude Opus 5.5 (wrote these tests, #359); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.ui.friend

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.SendFriendRequestResult
import com.polysocial.model.qr.friendQrPayload
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.utils.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanFriendViewModelTest {
  @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("sports"))
  private val created = Instant.parse("2026-11-16T10:00:00Z")

  private val auth = FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true))
  private val bob = PublicProfile("bob", "Bob Keller", "SC", "MA1", emptySet())
  private val profiles = FakePublicProfileRepository(mutableMapOf("lea" to lea, "bob" to bob))
  private val friends = FakeFriendRepository(currentUid = "alice", now = created)

  private fun viewModel() = ScanFriendViewModel(auth, profiles, friends)

  private fun code(uid: String) = friendQrPayload(uid)!!

  private fun request(from: String, status: FriendshipStatus = FriendshipStatus.PENDING) =
      Friendship(
          id = "alice~lea",
          uids = listOf("alice", "lea"),
          requestedBy = from,
          status = status,
          createdAt = created,
          acceptedAt = null,
      )

  private fun card(button: FriendButton, error: FriendActionError? = null) =
      ScanFriendUiState.Card(lea, button, busy = false, error = error)

  @Test
  fun startsScanning() {
    assertEquals(ScanFriendUiState.Scanning, viewModel().uiState.value)
  }

  @Test
  fun aFriendCode_showsTheStudentsPublicProfileWithAddFriend() {
    val vm = viewModel()

    vm.onCodeScanned(code("lea"))

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
    assertEquals(listOf(listOf("lea")), profiles.requestedUids)
  }

  @Test
  fun anythingButAFriendCode_isAnInvalidCodeWithoutReadingAProfile() {
    for (raw in
        listOf("https://epfl.ch", "", "polysocial://checkin/e1/t1", "polysocial://friend/")) {
      val vm = viewModel()

      vm.onCodeScanned(raw)

      assertEquals(raw, ScanFriendUiState.Problem(ScanProblem.INVALID_CODE), vm.uiState.value)
    }
    assertEquals(emptyList<Collection<String>>(), profiles.requestedUids)
  }

  @Test
  fun yourOwnCode_isAProblemWithoutReadingAProfile() {
    val vm = viewModel()

    vm.onCodeScanned(code("alice"))

    assertEquals(ScanFriendUiState.Problem(ScanProblem.OWN_CODE), vm.uiState.value)
    assertEquals(emptyList<Collection<String>>(), profiles.requestedUids)
  }

  @Test
  fun aUidWithoutAPublicProfile_isNotFound() {
    // An association, a deleted account or a made-up code.
    val vm = viewModel()

    vm.onCodeScanned(code("club"))

    assertEquals(ScanFriendUiState.Problem(ScanProblem.NOT_FOUND), vm.uiState.value)
  }

  @Test
  fun aFailedProfileRead_couldNotLoad() {
    for (failure in FakePublicProfileRepository.Failure.entries) {
      profiles.failure = failure
      val vm = viewModel()

      vm.onCodeScanned(code("lea"))

      assertEquals(ScanFriendUiState.Problem(ScanProblem.COULD_NOT_LOAD), vm.uiState.value)
    }
  }

  @Test
  fun furtherScans_areIgnoredUntilScanAgain() {
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    vm.onCodeScanned(code("alice"))
    vm.onCodeScanned("https://epfl.ch")

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
    assertEquals(1, profiles.requestedUids.size)
  }

  @Test
  fun scanAgain_returnsToScanningFromAProblemOrACard() {
    val vm = viewModel()
    vm.onCodeScanned("not a code")
    vm.onScanAgain()
    assertEquals(ScanFriendUiState.Scanning, vm.uiState.value)

    vm.onCodeScanned(code("lea"))
    vm.onScanAgain()
    assertEquals(ScanFriendUiState.Scanning, vm.uiState.value)
  }

  @Test
  fun theButtonFollowsTheLiveFriendship() {
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    friends.seedFriendship(request(from = "alice"))
    assertEquals(card(FriendButton.SENT), vm.uiState.value)

    friends.seedFriendship(request(from = "alice", status = FriendshipStatus.ACCEPTED))
    assertEquals(card(FriendButton.FRIENDS), vm.uiState.value)

    friends.seedFriendship(request(from = "lea"))
    assertEquals(card(FriendButton.ACCEPT), vm.uiState.value)
  }

  @Test
  fun anotherStudentsFriendship_doesNotChangeTheButton() {
    friends.seedFriendship(
        request(from = "alice").copy(id = "alice~bob", uids = listOf("alice", "bob"))
    )
    val vm = viewModel()

    vm.onCodeScanned(code("lea"))

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
  }

  @Test
  fun addFriend_sendsTheRequest_andShowsRequestSent() {
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    vm.onButton()

    assertEquals(FriendshipStatus.PENDING, friends.friendship("alice~lea")?.status)
    assertEquals("alice", friends.friendship("alice~lea")?.requestedBy)
    assertEquals(card(FriendButton.SENT), vm.uiState.value)
  }

  @Test
  fun addFriend_offline_saysSoAndKeepsAddFriendForTryAgain() {
    friends.sendFailure = SendFriendRequestResult.NetworkError
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    vm.onButton()
    assertEquals(card(FriendButton.ADD, FriendActionError.OFFLINE), vm.uiState.value)

    friends.sendFailure = null
    vm.onButton()
    assertEquals(card(FriendButton.SENT), vm.uiState.value)
  }

  @Test
  fun addFriend_anUnexpectedFailure_saysItFailed() {
    friends.sendFailure = SendFriendRequestResult.UnexpectedError
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    vm.onButton()

    assertEquals(card(FriendButton.ADD, FriendActionError.FAILED), vm.uiState.value)
  }

  @Test
  fun addFriend_whenARequestAlreadyExists_writesNothingAndKeepsTheListenersButton() {
    // Their request isn't on screen yet (the listener runs but hasn't delivered it): the send is
    // AlreadyExists, nothing is written, and the listener shows the request a moment later.
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    friends.sendFailure = SendFriendRequestResult.AlreadyExists

    vm.onButton()

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
    assertEquals(null, friends.friendship("alice~lea"))
  }

  @Test
  fun aStoppedListener_anExistingRequest_saysItFailedAndListensAgain() {
    // The listener ended on an error, so it will never show Léa's request: tapping says it failed
    // instead of doing nothing, and starts listening again, which shows the request.
    friends.readFailure = true
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    friends.seedFriendship(request(from = "lea"))
    friends.readFailure = false

    vm.onButton()

    assertEquals(card(FriendButton.ACCEPT), vm.uiState.value)
    assertEquals("lea", friends.friendship("alice~lea")?.requestedBy)
  }

  @Test
  fun aStoppedListener_thatFailsAgain_keepsSayingItFailed_untilATapWorks() {
    friends.readFailure = true
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    friends.seedFriendship(request(from = "lea"))

    vm.onButton()
    assertEquals(card(FriendButton.ADD, FriendActionError.FAILED), vm.uiState.value)

    friends.readFailure = false
    vm.onButton()
    assertEquals(card(FriendButton.ACCEPT), vm.uiState.value)
  }

  @Test
  fun aStoppedListener_aRequestCancelledBeforeAccept_saysItFailedAndListensAgain() {
    friends.seedFriendship(request(from = "lea"))
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    // The listener delivered Léa's request, then stopped; she cancels it, unseen.
    friends.failListeners()
    friends.deleteFriendship("alice~lea")
    friends.readFailure = true

    vm.onButton()
    assertEquals(card(FriendButton.ACCEPT, FriendActionError.FAILED), vm.uiState.value)

    friends.readFailure = false
    vm.onButton()
    assertEquals(card(FriendButton.ADD), vm.uiState.value)
  }

  @Test
  fun addFriend_aLateSentResponse_keepsFriendsWhenTheyAcceptedMeanwhile() {
    // The server stores the request, Léa accepts it and the listener shows Friends, all before
    // the send call returns: its Sent is older than what the listener shows.
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    val response = CompletableDeferred<Unit>()
    friends.sendResponseGate = response

    vm.onButton()
    friends.seedFriendship(request(from = "alice", status = FriendshipStatus.ACCEPTED))
    response.complete(Unit)

    assertEquals(card(FriendButton.FRIENDS), vm.uiState.value)
  }

  @Test
  fun addFriend_aLateSentResponse_keepsAddFriendWhenTheyDeclinedMeanwhile() {
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    val response = CompletableDeferred<Unit>()
    friends.sendResponseGate = response

    vm.onButton()
    friends.deleteFriendship("alice~lea")
    response.complete(Unit)

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
  }

  @Test
  fun addFriend_withoutTheListener_showsRequestSentFromTheResponse() {
    // The listener never shows the request, so the response is the newest news.
    friends.readFailure = true
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    vm.onButton()

    assertEquals(card(FriendButton.SENT), vm.uiState.value)
  }

  @Test
  fun accept_aLateDoneResponse_keepsAddFriendWhenTheyRemovedThemMeanwhile() {
    friends.seedFriendship(request(from = "lea"))
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    val response = CompletableDeferred<Unit>()
    friends.acceptResponseGate = response

    vm.onButton()
    friends.deleteFriendship("alice~lea")
    response.complete(Unit)

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
  }

  @Test
  fun aLateResponse_forAStudentNoLongerShown_leavesTheNewCardAlone() {
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    val response = CompletableDeferred<Unit>()
    friends.sendResponseGate = response
    vm.onButton()

    vm.onScanAgain()
    vm.onCodeScanned(code("bob"))
    response.complete(Unit)

    assertEquals(ScanFriendUiState.Card(bob, FriendButton.ADD), vm.uiState.value)
  }

  @Test
  fun accept_makesThemFriends() {
    friends.seedFriendship(request(from = "lea"))
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    assertEquals(card(FriendButton.ACCEPT), vm.uiState.value)

    vm.onButton()

    assertEquals(FriendshipStatus.ACCEPTED, friends.friendship("alice~lea")?.status)
    assertEquals(card(FriendButton.FRIENDS), vm.uiState.value)
  }

  @Test
  fun accept_offlineOrFailed_saysSo() {
    friends.seedFriendship(request(from = "lea"))
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    friends.acceptFailure = FriendshipActionResult.NetworkError
    vm.onButton()
    assertEquals(card(FriendButton.ACCEPT, FriendActionError.OFFLINE), vm.uiState.value)

    friends.acceptFailure = FriendshipActionResult.UnexpectedError
    vm.onButton()
    assertEquals(card(FriendButton.ACCEPT, FriendActionError.FAILED), vm.uiState.value)
  }

  @Test
  fun accept_ofARequestCancelledMeanwhile_showsTheCurrentState() {
    friends.seedFriendship(request(from = "lea"))
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))
    friends.acceptFailure = FriendshipActionResult.Gone

    vm.onButton()

    assertEquals(card(FriendButton.ACCEPT), vm.uiState.value)
  }

  @Test
  fun requestSentAndFriends_doNothingOnTap() {
    friends.seedFriendship(request(from = "alice"))
    val vm = viewModel()
    vm.onCodeScanned(code("lea"))

    vm.onButton()

    assertEquals(card(FriendButton.SENT), vm.uiState.value)
    assertEquals(FriendshipStatus.PENDING, friends.friendship("alice~lea")?.status)
  }

  @Test
  fun aTapWhileNoCardIsShown_doesNothing() {
    val vm = viewModel()

    vm.onButton()

    assertEquals(ScanFriendUiState.Scanning, vm.uiState.value)
    assertEquals(null, friends.friendship("alice~lea"))
  }

  @Test
  fun aFailedFriendshipListener_stillShowsTheCardWithAddFriend() {
    friends.readFailure = true
    val vm = viewModel()

    vm.onCodeScanned(code("lea"))

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
  }

  @Test
  fun nobodySignedIn_noCodeCountsAsYourOwn() {
    auth.user = null
    friends.currentUid = null
    val vm = viewModel()

    vm.onCodeScanned(code("lea"))

    assertEquals(card(FriendButton.ADD), vm.uiState.value)
  }
}
