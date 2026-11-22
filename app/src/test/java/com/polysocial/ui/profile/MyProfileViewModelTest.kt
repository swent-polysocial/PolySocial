// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the signed-in student's own
// profile, #495).
package com.polysocial.ui.profile

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyProfileViewModelTest {
  @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

  private val me = PublicProfile("me", "Demo Student", "IN", "BA3", setOf("sports"))
  private val auth = FakeAuthRepository(AuthUser("me", "demo@epfl.ch", true, "Demo Student"))
  private val profiles = FakePublicProfileRepository(mutableMapOf("me" to me))

  private fun viewModel(repository: PublicProfileRepository = profiles) =
      MyProfileViewModel(auth, repository)

  @Test
  fun startsLoading_thenShowsTheSignedInStudentsOwnPublicProfile() =
      runTest(main.dispatcher) {
        val vm = viewModel()
        assertEquals(StudentProfileUiState.Loading, vm.uiState.value)

        vm.refresh()
        runCurrent()

        assertEquals(StudentProfileUiState.Found(me), vm.uiState.value)
        assertEquals(listOf(listOf("me")), profiles.requestedUids)
      }

  @Test
  fun aMissingPublicProfile_isNotFound_neverReadingAnotherStudent() =
      runTest(main.dispatcher) {
        profiles.profiles.clear()
        val vm = viewModel()

        vm.refresh()
        runCurrent()

        assertEquals(StudentProfileUiState.NotFound, vm.uiState.value)
        assertEquals(listOf(listOf("me")), profiles.requestedUids)
      }

  @Test
  fun aFailedRead_offlineOrUnexpected_couldNotLoad() =
      runTest(main.dispatcher) {
        for (failure in FakePublicProfileRepository.Failure.entries) {
          profiles.failure = failure
          val vm = viewModel()

          vm.refresh()
          runCurrent()

          assertEquals("$failure", StudentProfileUiState.CouldNotLoad, vm.uiState.value)
        }
      }

  @Test
  fun offline_withTheProfileCached_showsTheCachedProfile() =
      runTest(main.dispatcher) {
        val vm = viewModel(OfflineWithCache(PublicProfileResult.Found(me)))

        vm.refresh()
        runCurrent()

        assertEquals(StudentProfileUiState.Found(me), vm.uiState.value)
      }

  @Test
  fun nobodySignedIn_couldNotLoad_withoutReading() =
      runTest(main.dispatcher) {
        auth.user = null
        val vm = viewModel()

        vm.refresh()
        runCurrent()

        assertEquals(StudentProfileUiState.CouldNotLoad, vm.uiState.value)
        assertEquals(emptyList<Collection<String>>(), profiles.requestedUids)
      }

  @Test
  fun retry_afterAFailure_readsAgainAndShowsTheProfile() =
      runTest(main.dispatcher) {
        profiles.failure = FakePublicProfileRepository.Failure.Offline
        val vm = viewModel()
        vm.refresh()
        runCurrent()
        profiles.failure = null

        vm.retry()
        assertEquals(StudentProfileUiState.Loading, vm.uiState.value)
        runCurrent()

        assertEquals(StudentProfileUiState.Found(me), vm.uiState.value)
      }

  @Test
  fun retry_inAnyOtherState_doesNotReadAgain() =
      runTest(main.dispatcher) {
        val vm = viewModel()
        vm.refresh()
        runCurrent()

        vm.retry()
        runCurrent()

        assertEquals(StudentProfileUiState.Found(me), vm.uiState.value)
        assertEquals(listOf(listOf("me")), profiles.requestedUids)
      }

  @Test
  fun refreshing_keepsTheProfileOnScreenUntilTheNewAnswerArrives() =
      runTest(main.dispatcher) {
        val vm = viewModel()
        vm.refresh()
        runCurrent()
        val edited = me.copy(interests = setOf("sports", "cinema"))
        profiles.profiles["me"] = edited

        vm.refresh()
        assertEquals(StudentProfileUiState.Found(me), vm.uiState.value)
        runCurrent()

        assertEquals(StudentProfileUiState.Found(edited), vm.uiState.value)
      }

  @Test
  fun aSlowerEarlierRead_neverOverwritesTheLatestOne() =
      runTest(main.dispatcher) {
        val slow = CompletableDeferred<PublicProfileResult>()
        val repository = FirstReadSlow(slow, PublicProfileResult.Found(me))
        val vm = viewModel(repository)
        vm.refresh()
        runCurrent()

        vm.refresh()
        runCurrent()
        slow.complete(PublicProfileResult.NotFound)
        runCurrent()

        assertEquals(StudentProfileUiState.Found(me), vm.uiState.value)
      }

  /** The server can't be reached, but the device's cache holds [cached]. */
  private class OfflineWithCache(private val cached: PublicProfileResult) :
      PublicProfileRepository {
    override suspend fun getPublicProfile(uid: String): PublicProfileResult =
        PublicProfileResult.NetworkError

    override suspend fun getCachedPublicProfile(uid: String): PublicProfileResult = cached

    override suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult =
        PublicProfilesResult.NetworkError
  }

  /** The first read waits for [first]; later reads answer [later] at once. */
  private class FirstReadSlow(
      private val first: CompletableDeferred<PublicProfileResult>,
      private val later: PublicProfileResult,
  ) : PublicProfileRepository {
    private var reads = 0

    override suspend fun getPublicProfile(uid: String): PublicProfileResult =
        if (reads++ == 0) first.await() else later

    override suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult =
        PublicProfilesResult.NetworkError
  }
}
