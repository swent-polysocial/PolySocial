// Contributors: OpenAI Codex (adapted association routing tests to setup recovery).
// Contributors: OpenAI Codex (late profile completion cannot reopen a signed-out session, #107).
// Contributors: Claude Opus 5.5 (wrote these tests; refresh after Log in; full profile fixture,
// #34); Claude (log out and the created profile; Verify Email opened in-session, #74; routing
// of association accounts, #127).
// Contributors: Claude (Opus 5.5, Anthropic) passed a fake session guard to app start and tested
// waiting for it, #198; Mohamed Khellaf (reviewed and revised).
package com.polysocial.ui.start

import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.firebase.SessionGuardResult
import com.polysocial.model.user.AccountType
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
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()

    assertEquals(StartDestination.Login, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun unverifiedUser_routesToVerifyEmailWithoutLoadingAProfile() = runTest {
    auth.user = verified.copy(isEmailVerified = false)

    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()

    assertEquals(StartDestination.VerifyEmail, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun verifiedUserWithProfile_routesToMainAfterLoadingTheProfileOnce() = runTest {
    auth.user = verified
    profiles.getProfileResult = found

    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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

    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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

    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()

    assertEquals(StartDestination.ProfileSetup, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun profileNetworkError_routesToError() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NetworkError

    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()

    assertEquals(StartDestination.Error, viewModel.destination.value)
  }

  @Test
  fun profileUnexpectedError_routesToError() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.UnexpectedError

    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()

    assertEquals(StartDestination.Error, viewModel.destination.value)
  }

  @Test
  fun refreshAfterError_loadsTheProfileAgainAndRoutesToMain() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NetworkError
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()

    auth.user = verified.copy(isEmailVerified = false)
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.VerifyEmail, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun lateProfileCompletionAfterLeavingSetupCannotReopenTheApp() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NotFound
    val vm =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.ProfileSetup, vm.destination.value)

    vm.logOut()
    vm.onProfileCreated()
    advanceUntilIdle()

    assertEquals(StartDestination.Login, vm.destination.value)
    assertEquals(null, auth.currentUser())
    assertEquals(1, profiles.getProfileCalls)
    assertEquals(1, auth.logOutCalls)
  }

  @Test
  fun logOut_nextLaunchRoutesToLogin() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val firstLaunch =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Main, firstLaunch.destination.value)

    auth.logOut()
    val nextLaunch =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()

    assertEquals(StartDestination.Login, nextLaunch.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun logOut_signsOutAndRoutesToLogin() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
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
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.ProfileSetup, viewModel.destination.value)

    viewModel.onProfileCreated()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun onProfileCreated_outsideTheProfileStep_changesNothing() = runTest {
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Login, viewModel.destination.value)

    viewModel.onProfileCreated()

    // Only the profile step may open the app this way, never a signed-out user.
    assertEquals(StartDestination.Login, viewModel.destination.value)
  }

  @Test
  fun onVerificationNeeded_whenSignedOut_opensVerifyEmailAndLogOutReturnsToLogin() = runTest {
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Login, viewModel.destination.value)

    auth.user = verified.copy(isEmailVerified = false)
    viewModel.onVerificationNeeded()
    advanceUntilIdle()
    assertEquals(StartDestination.VerifyEmail, viewModel.destination.value)

    // The move back to Login is what lets the signed-out start reset the forms.
    viewModel.logOut()
    assertEquals(StartDestination.Login, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun onVerificationNeeded_whenSignedIn_changesNothing() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Main, viewModel.destination.value)

    viewModel.onVerificationNeeded()

    // A verified session must never be sent back to Verify Email.
    assertEquals(StartDestination.Main, viewModel.destination.value)
  }

  private fun association(verified: Boolean, email: String = "club@example.org") =
      ProfileResult.Found(
          found.profile.copy(
              email = email,
              accountType = AccountType.ASSOCIATION,
              isAssociationVerified = verified,
          )
      )

  private fun routeOf(profile: ProfileResult): StartDestination {
    auth.user = verified
    profiles.getProfileResult = profile
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            EmptyAssociationSignupStore,
            FakeAssociationRepository(
                com.polysocial.model.association.AssociationResult.Found(
                    com.polysocial.model.association.Association(
                        "u1",
                        "Club",
                        com.polysocial.model.association.AssociationType.CLUB,
                        "About",
                    )
                )
            ),
            sessions = FakeSessionGuard(),
        )
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    return viewModel.destination.value
  }

  @Test
  fun anAssociationAnAdminHasntVerified_routesToItsPendingApp() {
    assertEquals(StartDestination.PendingAssociation, routeOf(association(verified = false)))
  }

  @Test
  fun aVerifiedAssociation_routesToTheMainApp_neverToThePendingOne() {
    assertEquals(StartDestination.AssociationMain, routeOf(association(verified = true)))
  }

  @Test
  fun aStudent_routesToTheMainApp_evenWithTheFlagSetByMistake() {
    // isAssociationVerified only counts on an association's account.
    val student = ProfileResult.Found(found.profile.copy(isAssociationVerified = true))

    assertEquals(StartDestination.Main, routeOf(found))
    assertEquals(StartDestination.Main, routeOf(student))
  }

  @Test
  fun anAssociationWithAnEpflEmail_isStillAnAssociation() {
    // Classification follows the account type, not the email (#161).
    assertEquals(
        StartDestination.PendingAssociation,
        routeOf(association(verified = false, email = "club@epfl.ch")),
    )
  }

  @Test
  fun onceAnAdminVerifiesIt_theNextStartOpensTheMainApp() {
    assertEquals(StartDestination.PendingAssociation, routeOf(association(verified = false)))
    // The admin sets the flag (#135); the app starts again.
    assertEquals(StartDestination.AssociationMain, routeOf(association(verified = true)))
  }

  @Test
  fun aPendingAssociationLoggingOut_returnsToLogin_andRefreshRoutesItBackAfterLogIn() {
    auth.user = verified
    profiles.getProfileResult = association(verified = false)
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            EmptyAssociationSignupStore,
            FakeAssociationRepository(
                com.polysocial.model.association.AssociationResult.Found(
                    com.polysocial.model.association.Association(
                        "u1",
                        "Club",
                        com.polysocial.model.association.AssociationType.CLUB,
                        "About",
                    )
                )
            ),
            sessions = FakeSessionGuard(),
        )
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    viewModel.logOut()
    assertEquals(StartDestination.Login, viewModel.destination.value)

    auth.user = verified
    viewModel.refresh()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(StartDestination.PendingAssociation, viewModel.destination.value)
  }

  // ---- session guard (#198) ----

  @Test
  fun verifiedUser_readsTheProfileOnlyOnceTheCacheIsSafeForTheAccount() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val guard = FakeSessionGuard().apply { gate = CompletableDeferred() }
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = guard,
        )
    advanceUntilIdle()

    assertEquals(StartDestination.Loading, viewModel.destination.value)
    assertEquals(listOf<String?>("u1"), guard.requestedUids)
    assertEquals(0, profiles.getProfileCalls)

    guard.gate?.complete(Unit)
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun failedCacheClear_showsTheProfileErrorWithoutReading() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val guard = FakeSessionGuard(SessionGuardResult.CleanupFailed)

    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = guard,
        )
    advanceUntilIdle()

    assertEquals(StartDestination.Error, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun retryAfterAFailedCacheClear_triesTheClearAgainThenReads() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val guard = FakeSessionGuard(SessionGuardResult.CleanupFailed)
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = guard,
        )
    advanceUntilIdle()

    guard.result = SessionGuardResult.Ready
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(listOf<String?>("u1", "u1"), guard.requestedUids)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun retryWhileTheClearStillFails_staysOnTheError() = runTest {
    auth.user = verified
    val guard = FakeSessionGuard(SessionGuardResult.CleanupFailed)
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = guard,
        )
    advanceUntilIdle()

    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.Error, viewModel.destination.value)
    assertEquals(2, guard.calls)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun logOutWhileWaitingForTheGuard_neverReadsTheProfile() = runTest {
    auth.user = verified
    profiles.getProfileResult = found
    val guard = FakeSessionGuard().apply { gate = CompletableDeferred() }
    val viewModel =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = guard,
        )
    advanceUntilIdle()

    viewModel.logOut()
    guard.gate?.complete(Unit)
    advanceUntilIdle()

    assertEquals(StartDestination.Login, viewModel.destination.value)
    assertEquals(0, profiles.getProfileCalls)
  }
}
