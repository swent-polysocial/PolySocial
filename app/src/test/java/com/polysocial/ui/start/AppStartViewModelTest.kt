// Contributors: Claude Opus 5.5 (wrote these tests; refresh after Log in; full profile fixture,
// #34); Claude (log out and the created profile, #74).
package com.polysocial.ui.start

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppStartViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val verified = AuthUser(uid = "u1", email = "a@epfl.ch", isEmailVerified = true)
  private val found =
      ProfileResult.Found(
          UserProfile(
              uid = "u1",
              email = "a@epfl.ch",
              displayName = "Test Student",
              section = "IN",
              year = "BA3",
          )
      )

  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()

  @Test
  fun noUser_routesToLoginWithoutLoadingAProfile() = runTest {
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(StartDestination.Login, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun unverifiedUser_routesToVerifyEmailWithoutLoadingAProfile() = runTest {
    auth.user = verified.copy(isEmailVerified = false)

    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(StartDestination.VerifyEmail, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun verifiedUserWithProfile_routesToMainAfterLoadingTheProfileOnce() = runTest {
    auth.user = verified
    profiles.getProfileResult = found

    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
    assertEquals("u1", profiles.lastRequestedUid)
  }

  @Test
  fun verifiedUser_staysLoadingUntilTheProfileIsLoaded() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val gate = CompletableDeferred<Unit>()
    profiles.gate = gate

    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(1, profiles.getProfileCalls)
    assertEquals(StartDestination.Loading, viewModel.destination.value)

    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun verifiedUserWithoutProfile_routesToProfileSetup() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NotFound

    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(StartDestination.ProfileSetup, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun profileNetworkError_routesToError() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NetworkError

    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(StartDestination.Error, viewModel.destination.value)
  }

  @Test
  fun profileUnexpectedError_routesToError() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.UnexpectedError

    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(StartDestination.Error, viewModel.destination.value)
  }

  @Test
  fun refreshAfterError_loadsTheProfileAgainAndRoutesToMain() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NetworkError
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    profiles.getProfileResult = found

    viewModel.refresh()

    assertEquals(StartDestination.Loading, viewModel.destination.value)
    advanceUntilIdle()
    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(2, profiles.getProfileCalls)
  }

  @Test
  fun refreshWhileTheProfileIsLoading_doesNotLoadItTwice() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    profiles.gate = CompletableDeferred()
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    viewModel.refresh()
    viewModel.refresh()
    profiles.gate?.complete(Unit)
    advanceUntilIdle()

    assertEquals(1, profiles.getProfileCalls)
    assertEquals(StartDestination.Main, viewModel.destination.value)
  }

  @Test
  fun refreshAfterLogIn_routesTheNewUserToTheProfileStep() = runTest {
    profiles.getProfileResult = ProfileResult.NotFound
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(StartDestination.Login, viewModel.destination.value)

    auth.user = verified
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.ProfileSetup, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun refreshAfterAnUnverifiedLogIn_routesToVerifyEmail() = runTest {
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    auth.user = verified.copy(isEmailVerified = false)
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.VerifyEmail, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun logOut_nextLaunchRoutesToLogin() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val firstLaunch = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(StartDestination.Main, firstLaunch.destination.value)

    auth.logOut()
    val nextLaunch = AppStartViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(StartDestination.Login, nextLaunch.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun logOut_signsOutAndRoutesToLogin() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(StartDestination.Main, viewModel.destination.value)

    viewModel.logOut()

    assertEquals(StartDestination.Login, viewModel.destination.value)
    assertEquals(1, auth.logOutCalls)
    assertEquals(null, auth.currentUser())
  }

  @Test
  fun logOutWhileTheProfileIsLoading_ignoresTheLateResult() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    profiles.gate = CompletableDeferred()
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(StartDestination.Loading, viewModel.destination.value)

    viewModel.logOut()
    profiles.gate?.complete(Unit)
    advanceUntilIdle()

    // The read finished after the sign-out: it must not open the app for nobody.
    assertEquals(StartDestination.Login, viewModel.destination.value)
  }

  @Test
  fun refreshAfterLogOut_routesTheNextAccount() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    viewModel.logOut()

    auth.user = verified.copy(uid = "u2", email = "b@epfl.ch")
    profiles.getProfileResult = ProfileResult.NotFound
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.ProfileSetup, viewModel.destination.value)
    assertEquals("u2", profiles.lastRequestedUid)
  }

  @Test
  fun onProfileCreated_opensTheMainAppWithoutReadingTheProfileAgain() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NotFound
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(StartDestination.ProfileSetup, viewModel.destination.value)

    viewModel.onProfileCreated()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun onProfileCreated_outsideTheProfileStep_changesNothing() = runTest {
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(StartDestination.Login, viewModel.destination.value)

    viewModel.onProfileCreated()

    // Only the profile step may open the app this way, never a signed-out user.
    assertEquals(StartDestination.Login, viewModel.destination.value)
  }
}
