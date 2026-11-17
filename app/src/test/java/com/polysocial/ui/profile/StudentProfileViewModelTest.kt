// Contributors: Claude Opus 5.5 (wrote these tests, #368, and the cached-profile tests after
// review); Konstantinos Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.profile

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudentProfileViewModelTest {
  @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("sports"))
  private val profiles = FakePublicProfileRepository(mutableMapOf("lea" to lea))

  private fun viewModel(uid: String? = "lea", repository: PublicProfileRepository = profiles) =
      StudentProfileViewModel(
          SavedStateHandle(
              if (uid == null) emptyMap() else mapOf(StudentProfileViewModel.UID_ARG to uid)
          ),
          repository,
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

        advanceTimeBy(StudentProfileViewModel.TIMEOUT_MILLIS - 1)
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
        advanceTimeBy(StudentProfileViewModel.TIMEOUT_MILLIS - 1)

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

        advanceTimeBy(StudentProfileViewModel.TIMEOUT_MILLIS)
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

          advanceTimeBy(StudentProfileViewModel.TIMEOUT_MILLIS)
          runCurrent()

          assertEquals("$cached", StudentProfileUiState.CouldNotLoad, vm.uiState.value)
        }
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
        advanceTimeBy(StudentProfileViewModel.TIMEOUT_MILLIS)
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
}
