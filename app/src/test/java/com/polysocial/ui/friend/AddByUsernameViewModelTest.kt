// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, test-first, #452); Konstantinos
// Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.friend

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.FakeUsernameRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.UsernameLookupResult
import com.polysocial.model.user.UsernameRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Add by @username (#452): one lookup per submitted name, then the shared card. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddByUsernameViewModelTest {
  @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

  private val nora = PublicProfile("nora", "Nora Meier", "IN", "BA3", setOf("sports"))
  private val auth = FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true))
  private val names =
      FakeUsernameRepository(currentUid = "alice").apply {
        names["nora_meier"] = "nora"
        names["alice.b"] = "alice"
        names["ghost"] = "deleted-student"
      }
  private val profiles = FakePublicProfileRepository(mutableMapOf("nora" to nora))
  private val friends =
      FakeFriendRepository(currentUid = "alice", now = Instant.parse("2026-11-24T18:00:00Z"))

  /** When set, [lookUp] waits for it before answering, like a slow connection. */
  private var lookUpGate: CompletableDeferred<Unit>? = null

  /** The answers of the next lookups, in call order, each given when the test completes it. */
  private val lookUpAnswers = ArrayDeque<CompletableDeferred<UsernameLookupResult>>()
  private val usernames =
      object : UsernameRepository by names {
        override suspend fun lookUp(name: String): UsernameLookupResult {
          lookUpAnswers.removeFirstOrNull()?.let {
            return it.await()
          }
          lookUpGate?.await()
          return names.lookUp(name)
        }
      }

  private fun viewModel() = AddByUsernameViewModel(auth, usernames, profiles, friends)

  private fun AddByUsernameViewModel.lookUp(input: String) {
    onInput(input)
    onLookUp()
  }

  @Test
  fun aNameSomeoneHolds_opensTheirCard_withAddFriend() {
    val vm = viewModel()

    vm.lookUp("nora_meier")

    assertEquals(FriendCard(nora, FriendButton.ADD), vm.uiState.value.card)
    assertNull(vm.uiState.value.problem)
    assertFalse(vm.uiState.value.looking)
  }

  @Test
  fun theTypedName_isCleanedBeforeTheLookup() {
    val vm = viewModel()

    vm.lookUp("  @Nora_Meier ")

    assertEquals(listOf("nora_meier"), names.lookedUp)
    assertEquals(nora, vm.uiState.value.card?.profile)
  }

  @Test
  fun aPastedAt_isDropped_sinceTheFieldShowsOne() {
    val vm = viewModel()

    vm.onInput("@nora_meier")

    assertEquals("nora_meier", vm.uiState.value.input)
  }

  @Test
  fun yourOwnName_saysSo_withoutReadingAProfileOrShowingACard() {
    val vm = viewModel()

    vm.lookUp("alice.b")

    assertEquals(UsernameLookupProblem.OWN_NAME, vm.uiState.value.problem)
    assertNull(vm.uiState.value.card)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun aNameNobodyHolds_orThatCantBeAUsername_isNotFound() {
    val vm = viewModel()

    vm.lookUp("nora.meierr")
    assertEquals(UsernameLookupProblem.NOT_FOUND, vm.uiState.value.problem)

    vm.lookUp("1abc")
    assertEquals(UsernameLookupProblem.NOT_FOUND, vm.uiState.value.problem)
    assertNull(vm.uiState.value.card)
  }

  @Test
  fun aNameWhoseStudentHasNoPublicProfile_isNotFound() {
    val vm = viewModel()

    vm.lookUp("ghost")

    assertEquals(UsernameLookupProblem.NOT_FOUND, vm.uiState.value.problem)
    assertNull(vm.uiState.value.card)
  }

  @Test
  fun aFailedLookup_orProfileRead_cantLookUp_andKeepsTheName() {
    val vm = viewModel()

    for (failure in
        listOf(UsernameLookupResult.NetworkError, UsernameLookupResult.UnexpectedError)) {
      names.lookUpFailure = failure
      vm.lookUp("nora_meier")
      assertEquals(UsernameLookupProblem.FAILED, vm.uiState.value.problem)
      assertEquals("nora_meier", vm.uiState.value.input)
    }

    names.lookUpFailure = null
    profiles.failure = FakePublicProfileRepository.Failure.Offline
    vm.lookUp("nora_meier")
    assertEquals(UsernameLookupProblem.FAILED, vm.uiState.value.problem)
    assertNull(vm.uiState.value.card)
  }

  @Test
  fun tryAgain_afterAFailure_looksUpAgain() {
    val vm = viewModel()
    names.lookUpFailure = UsernameLookupResult.NetworkError
    vm.lookUp("nora_meier")

    names.lookUpFailure = null
    vm.onLookUp()

    assertEquals(nora, vm.uiState.value.card?.profile)
    assertEquals(listOf("nora_meier", "nora_meier"), names.lookedUp)
  }

  @Test
  fun anEmptyName_looksNothingUp() {
    val vm = viewModel()

    vm.lookUp("  @ ")

    assertTrue(names.lookedUp.isEmpty())
    assertNull(vm.uiState.value.problem)
  }

  @Test
  fun whileALookupRuns_itIsShown_andASecondTapLooksNothingMoreUp() {
    val vm = viewModel()
    val gate = CompletableDeferred<Unit>()
    lookUpGate = gate

    vm.lookUp("nora_meier")
    assertTrue(vm.uiState.value.looking)
    vm.onLookUp()
    gate.complete(Unit)

    assertEquals(listOf("nora_meier"), names.lookedUp)
    assertFalse(vm.uiState.value.looking)
    assertEquals(nora, vm.uiState.value.card?.profile)
  }

  @Test
  fun editingTheName_clearsTheMessage() {
    val vm = viewModel()
    vm.lookUp("nobody_here")
    assertEquals(UsernameLookupProblem.NOT_FOUND, vm.uiState.value.problem)

    vm.onInput("nora_meie")

    assertNull(vm.uiState.value.problem)
    assertEquals("nora_meie", vm.uiState.value.input)
  }

  @Test
  fun editingTheNameWhileItIsLookedUp_dropsThatAnswer() {
    val vm = viewModel()
    val gate = CompletableDeferred<Unit>()
    lookUpGate = gate
    vm.lookUp("nora_meier")

    vm.onInput("someone_else")
    gate.complete(Unit)

    assertNull(vm.uiState.value.card)
    assertNull(vm.uiState.value.problem)
    assertFalse(vm.uiState.value.looking)
    assertEquals("someone_else", vm.uiState.value.input)
  }

  @Test
  fun anOlderLookupOfTheSameName_answeringLate_cantHideTheLatestOne() {
    val vm = viewModel()
    val older = CompletableDeferred<UsernameLookupResult>()
    val latest = CompletableDeferred<UsernameLookupResult>()
    lookUpAnswers.addAll(listOf(older, latest))
    vm.lookUp("nora_meier")
    vm.onInput("nora_meie")
    vm.lookUp("nora_meier")

    older.complete(UsernameLookupResult.NetworkError)
    latest.complete(UsernameLookupResult.Found("nora"))

    assertEquals(FriendCard(nora, FriendButton.ADD), vm.uiState.value.card)
    assertNull(vm.uiState.value.problem)
    assertFalse(vm.uiState.value.looking)
  }

  @Test
  fun theCard_sendsTheRequest_andFollowsTheFriendship() {
    val vm = viewModel()
    vm.lookUp("nora_meier")

    vm.onCardButton()

    assertEquals(FriendCard(nora, FriendButton.SENT), vm.uiState.value.card)
    assertEquals("alice", friends.friendship("alice~nora")?.requestedBy)
  }

  @Test
  fun theCard_ofAStudentWhoAlreadyAskedYou_offersAccept() {
    friends.seedFriendship(
        Friendship(
            id = "alice~nora",
            uids = listOf("alice", "nora"),
            requestedBy = "nora",
            status = FriendshipStatus.PENDING,
            createdAt = Instant.parse("2026-11-24T17:00:00Z"),
            acceptedAt = null,
        )
    )
    val vm = viewModel()

    vm.lookUp("nora_meier")

    assertEquals(FriendButton.ACCEPT, vm.uiState.value.card?.button)
  }

  @Test
  fun closingTheCard_keepsTheName() {
    val vm = viewModel()
    vm.lookUp("nora_meier")

    vm.onCloseCard()

    assertNull(vm.uiState.value.card)
    assertEquals("nora_meier", vm.uiState.value.input)
  }
}
