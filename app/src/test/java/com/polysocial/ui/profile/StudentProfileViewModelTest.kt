// Contributors: Claude Opus 5.5 (wrote these tests, #368, and the cached-profile tests after
// review); Konstantinos Perdikeas (directed, reviewed and owns them).
// Contributors: OpenAI Codex (6.2 Sol, high; cache fallback on a network error, #360 review).
// Contributors: Claude (Opus 5.5, Anthropic) (the friend action on a student's profile, #478);
// Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Opus 5.5, Anthropic) (the changed-request message and waiting for the
// friendship, #539 review); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.profile

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.SendFriendRequestResult
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.ui.friend.FriendActionError
import com.polysocial.ui.friend.FriendButton
import com.polysocial.ui.friend.FriendCard
import com.polysocial.utils.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudentProfileViewModelTest {
  @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("sports"))
  private val profiles = FakePublicProfileRepository(mutableMapOf("lea" to lea))

  private val auth = FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true))
  private val friends =
      FakeFriendRepository(currentUid = "alice", now = Instant.parse("2026-11-25T18:00:00Z"))

  private fun viewModel(
      uid: String? = "lea",
      repository: PublicProfileRepository = profiles,
      friendRepository: FriendRepository = friends,
  ) =
      StudentProfileViewModel(
          SavedStateHandle(
              if (uid == null) emptyMap() else mapOf(StudentProfileViewModel.UID_ARG to uid)
          ),
          repository,
          auth,
          friendRepository,
      )

  @Test
  fun loadsTheStudentsPublicProfileByUid() =
      runTest(main.dispatcher) {
        val vm = viewModel()
        assertEquals(StudentProfileUiState.Loading, vm.uiState.value)

        runCurrent()

        assertEquals(StudentProfileUiState.Found(lea), vm.uiState.value)
        assertEquals(listOf(listOf("lea")), profiles.requestedUids)
      }

  @Test
  fun aStudentWithoutAPublicProfile_isNotFound() =
      runTest(main.dispatcher) {
        val vm = viewModel(uid = "gone")

        runCurrent()

        assertEquals(StudentProfileUiState.NotFound, vm.uiState.value)
      }

  @Test
  fun aMissingOrBlankUid_isNotFoundWithoutReading() =
      runTest(main.dispatcher) {
        for (uid in listOf(null, "", " ")) {
          val vm = viewModel(uid = uid)

          runCurrent()

          assertEquals("$uid", StudentProfileUiState.NotFound, vm.uiState.value)
        }
        assertEquals(emptyList<Collection<String>>(), profiles.requestedUids)
      }

  @Test
  fun aFailedRead_offlineOrUnexpected_couldNotLoad() =
      runTest(main.dispatcher) {
        for (failure in FakePublicProfileRepository.Failure.entries) {
          profiles.failure = failure
          val vm = viewModel()

          runCurrent()

          assertEquals("$failure", StudentProfileUiState.CouldNotLoad, vm.uiState.value)
        }
      }

  @Test
  fun retry_afterAFailure_readsAgainAndShowsTheProfile() =
      runTest(main.dispatcher) {
        profiles.failure = FakePublicProfileRepository.Failure.Offline
        val vm = viewModel()
        runCurrent()
        profiles.failure = null

        vm.retry()
        assertEquals(StudentProfileUiState.Loading, vm.uiState.value)
        runCurrent()

        assertEquals(StudentProfileUiState.Found(lea), vm.uiState.value)
        assertEquals(listOf(listOf("lea"), listOf("lea")), profiles.requestedUids)
      }

  @Test
  fun retry_whileLoadingOrOnceLoaded_doesNotReadAgain() =
      runTest(main.dispatcher) {
        val vm = viewModel()
        vm.retry()
        runCurrent()
        vm.retry()
        runCurrent()

        assertEquals(StudentProfileUiState.Found(lea), vm.uiState.value)
        assertEquals(listOf(listOf("lea")), profiles.requestedUids)
      }

  @Test
  fun aReadThatNeverAnswers_couldNotLoadAfterTheTimeout() =
      runTest(main.dispatcher) {
        val vm = viewModel(repository = NeverAnswers)
        runCurrent()

        advanceTimeBy(PROFILE_READ_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertEquals(StudentProfileUiState.Loading, vm.uiState.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(StudentProfileUiState.CouldNotLoad, vm.uiState.value)
      }

  @Test
  fun aSlowReadThatAnswersBeforeTheTimeout_showsTheProfile() =
      runTest(main.dispatcher) {
        val answer = CompletableDeferred<PublicProfileResult>()
        val vm = viewModel(repository = Answers(answer))
        runCurrent()
        advanceTimeBy(PROFILE_READ_TIMEOUT_MILLIS - 1)

        answer.complete(PublicProfileResult.Found(lea))
        runCurrent()

        assertEquals(StudentProfileUiState.Found(lea), vm.uiState.value)
      }

  @Test
  fun aStalledConnection_withTheProfileCached_showsTheCachedProfileAtTheTimeout() =
      runTest(main.dispatcher) {
        // The group just read Léa's profile, then the connection stalled: the server read hangs
        // until Firestore notices it is offline, so the screen falls back to the cache.
        val stalled = Stalled(cached = PublicProfileResult.Found(lea))
        val vm = viewModel(repository = stalled)
        runCurrent()
        assertEquals(emptyList<String>(), stalled.cacheReads)

        advanceTimeBy(PROFILE_READ_TIMEOUT_MILLIS)
        runCurrent()

        assertEquals(StudentProfileUiState.Found(lea), vm.uiState.value)
        assertEquals(listOf("lea"), stalled.cacheReads)
      }

  @Test
  fun aStalledConnection_withNothingCached_couldNotLoad() =
      runTest(main.dispatcher) {
        for (cached in
            listOf(
                PublicProfileResult.NetworkError,
                PublicProfileResult.UnexpectedError,
                // Only the server can confirm a miss: a cached one may be out of date.
                PublicProfileResult.NotFound,
            )) {
          val vm = viewModel(repository = Stalled(cached))
          runCurrent()

          advanceTimeBy(PROFILE_READ_TIMEOUT_MILLIS)
          runCurrent()

          assertEquals("$cached", StudentProfileUiState.CouldNotLoad, vm.uiState.value)
        }
      }

  @Test
  fun aNetworkErrorWithACachedProfile_showsTheCachedProfile() =
      runTest(main.dispatcher) {
        val answer = CompletableDeferred<PublicProfileResult>(PublicProfileResult.NetworkError)
        val repository = Answers(answer, cached = PublicProfileResult.Found(lea))
        val vm = viewModel(repository = repository)

        runCurrent()

        assertEquals(StudentProfileUiState.Found(lea), vm.uiState.value)
        assertEquals(listOf("lea"), repository.cacheReads)
      }

  @Test
  fun aMissTheServerConfirms_isNotFound_withoutReadingTheCache() =
      runTest(main.dispatcher) {
        val answer = CompletableDeferred<PublicProfileResult>()
        val repository = Answers(answer, cached = PublicProfileResult.Found(lea))
        val vm = viewModel(repository = repository)
        runCurrent()

        answer.complete(PublicProfileResult.NotFound)
        runCurrent()
        advanceTimeBy(PROFILE_READ_TIMEOUT_MILLIS)
        runCurrent()

        assertEquals(StudentProfileUiState.NotFound, vm.uiState.value)
        assertEquals(emptyList<String>(), repository.cacheReads)
      }

  /** A read that never answers, like a request stuck on a bad network. */
  private object NeverAnswers : PublicProfileRepository {
    override suspend fun getPublicProfile(uid: String): PublicProfileResult = awaitCancellation()

    override suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult =
        awaitCancellation()
  }

  /**
   * A read that answers whatever [answer] completes with, when the test completes it; the cache
   * holds [cached].
   */
  private class Answers(
      private val answer: CompletableDeferred<PublicProfileResult>,
      private val cached: PublicProfileResult = PublicProfileResult.NetworkError,
  ) : PublicProfileRepository {
    val cacheReads = mutableListOf<String>()

    override suspend fun getPublicProfile(uid: String): PublicProfileResult = answer.await()

    override suspend fun getCachedPublicProfile(uid: String): PublicProfileResult {
      cacheReads += uid
      return cached
    }

    override suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult =
        awaitCancellation()
  }

  /** A server read stuck on a stalled connection, with [cached] in the device's cache. */
  private class Stalled(private val cached: PublicProfileResult) : PublicProfileRepository {
    val cacheReads = mutableListOf<String>()

    override suspend fun getPublicProfile(uid: String): PublicProfileResult = awaitCancellation()

    override suspend fun getCachedPublicProfile(uid: String): PublicProfileResult {
      cacheReads += uid
      return cached
    }

    override suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult =
        awaitCancellation()
  }

  // The friend action (#478, Figma #493): the shared card logic of the scan and Add by username.

  @Test
  fun anotherStudentsProfile_offersAddFriend_andSendsTheRequest() =
      runTest(main.dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(FriendCard(lea, FriendButton.ADD), vm.friendCard.value)

        vm.onFriendButton()
        runCurrent()

        assertEquals(FriendCard(lea, FriendButton.SENT), vm.friendCard.value)
        assertEquals("alice", friends.friendship("alice~lea")?.requestedBy)
      }

  @Test
  fun theButton_followsTheFriendship_requestSentAcceptOrFriends() =
      runTest(main.dispatcher) {
        friends.seedFriendship(pending(requestedBy = "lea"))
        val vm = viewModel()
        runCurrent()
        assertEquals(FriendButton.ACCEPT, vm.friendCard.value?.button)

        vm.onFriendButton()
        runCurrent()
        assertEquals(FriendButton.FRIENDS, vm.friendCard.value?.button)
      }

  @Test
  fun aRequestTheStudentSent_showsRequestSent() =
      runTest(main.dispatcher) {
        friends.seedFriendship(pending(requestedBy = "alice"))
        val vm = viewModel()
        runCurrent()

        assertEquals(FriendButton.SENT, vm.friendCard.value?.button)
      }

  @Test
  fun aRequestThatFails_offlineOrNot_saysSo_andTappingAgainRetries() =
      runTest(main.dispatcher) {
        val vm = viewModel()
        runCurrent()

        friends.sendFailure = SendFriendRequestResult.NetworkError
        vm.onFriendButton()
        runCurrent()
        assertEquals(
            FriendCard(lea, FriendButton.ADD, error = FriendActionError.OFFLINE),
            vm.friendCard.value,
        )

        friends.sendFailure = SendFriendRequestResult.UnexpectedError
        vm.onFriendButton()
        runCurrent()
        assertEquals(
            FriendCard(lea, FriendButton.ADD, error = FriendActionError.FAILED),
            vm.friendCard.value,
        )

        friends.sendFailure = null
        vm.onFriendButton()
        runCurrent()
        assertEquals(FriendCard(lea, FriendButton.SENT), vm.friendCard.value)
      }

  @Test
  fun yourOwnProfile_offersNoFriendAction() =
      runTest(main.dispatcher) {
        profiles.profiles["alice"] = PublicProfile("alice", "Alice B", "IN", "BA3", emptySet())
        val vm = viewModel(uid = "alice")
        runCurrent()

        assertTrue(vm.uiState.value is StudentProfileUiState.Found)
        assertNull(vm.friendCard.value)
        vm.onFriendButton()
        runCurrent()
        assertNull(friends.friendship("alice~alice"))
      }

  @Test
  fun noProfile_orStillLoading_offersNoFriendAction() =
      runTest(main.dispatcher) {
        val loading = viewModel()
        assertNull(loading.friendCard.value)

        val gone = viewModel(uid = "gone")
        runCurrent()
        assertEquals(StudentProfileUiState.NotFound, gone.uiState.value)
        assertNull(gone.friendCard.value)
      }

  @Test
  fun acceptingARequestWithdrawnMeanwhile_saysItChanged_andShowsAddFriend() =
      runTest(main.dispatcher) {
        friends.seedFriendship(pending(requestedBy = "lea"))
        val vm = viewModel()
        runCurrent()
        assertEquals(FriendButton.ACCEPT, vm.friendCard.value?.button)

        friends.acceptFailure = FriendshipActionResult.Gone
        vm.onFriendButton()
        friends.deleteFriendship("alice~lea")
        runCurrent()

        assertEquals(
            FriendCard(lea, FriendButton.ADD, error = FriendActionError.CHANGED),
            vm.friendCard.value,
        )
      }

  @Test
  fun theChangedMessage_staysWhenTheWithdrawalArrivesAfterTheResponse_untilTheNextTap() =
      runTest(main.dispatcher) {
        friends.seedFriendship(pending(requestedBy = "lea"))
        val vm = viewModel()
        runCurrent()

        friends.acceptFailure = FriendshipActionResult.Gone
        vm.onFriendButton()
        runCurrent()
        assertEquals(
            FriendCard(lea, FriendButton.ACCEPT, error = FriendActionError.CHANGED),
            vm.friendCard.value,
        )

        friends.deleteFriendship("alice~lea")
        runCurrent()
        assertEquals(
            FriendCard(lea, FriendButton.ADD, error = FriendActionError.CHANGED),
            vm.friendCard.value,
        )

        vm.onFriendButton()
        runCurrent()
        assertEquals(FriendCard(lea, FriendButton.SENT), vm.friendCard.value)
      }

  @Test
  fun aFriendsProfile_showsTheProfile_butNoActionUntilTheFriendshipLoads() =
      runTest(main.dispatcher) {
        friends.seedAcceptedFriendship("alice", "lea")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(friendRepository = LateListener(friends, gate))
        runCurrent()

        assertEquals(StudentProfileUiState.Found(lea), vm.uiState.value)
        assertNull(vm.friendCard.value)
        vm.onFriendButton()
        runCurrent()
        assertEquals(FriendshipStatus.ACCEPTED, friends.friendship("alice~lea")?.status)

        gate.complete(Unit)
        runCurrent()
        assertEquals(FriendCard(lea, FriendButton.FRIENDS), vm.friendCard.value)
      }

  @Test
  fun aListenerThatFailsBeforeAnswering_stillOffersAddFriend() =
      runTest(main.dispatcher) {
        friends.readFailure = true
        val vm = viewModel()
        runCurrent()

        assertEquals(FriendCard(lea, FriendButton.ADD), vm.friendCard.value)
      }

  /** [delegate], whose friendships listener delivers nothing until [gate] completes. */
  private class LateListener(
      private val delegate: FakeFriendRepository,
      private val gate: CompletableDeferred<Unit>,
  ) : FriendRepository by delegate {
    override fun observeFriendships(): Flow<FriendshipsResult> =
        delegate.observeFriendships().onStart { gate.await() }
  }

  private fun pending(requestedBy: String) =
      Friendship(
          id = "alice~lea",
          uids = listOf("alice", "lea"),
          requestedBy = requestedBy,
          status = FriendshipStatus.PENDING,
          createdAt = Instant.parse("2026-11-25T17:00:00Z"),
          acceptedAt = null,
      )
}
