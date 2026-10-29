// Contributors: OpenAI Codex (late profile completion cannot reopen a signed-out session, #107).
// Contributors: Claude Opus 5.5 (wrote these tests; refresh after Log in; full profile fixture,
// #34); Claude (log out and the created profile; Verify Email opened in-session, #74; routing
// of association accounts, #127; non-EPFL accounts that are not associations, #208); Claude
// (testing agent: look-alike domains, letter case and Retry for the non-EPFL error, #208).
package com.polysocial.ui.start

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
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
  fun lateProfileCompletionAfterLeavingSetupCannotReopenTheApp() = runTest {
    auth.user = verified
    profiles.getProfileResult = ProfileResult.NotFound
    val vm = AppStartViewModel(auth, profiles)
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

  @Test
  fun onVerificationNeeded_whenSignedOut_opensVerifyEmailAndLogOutReturnsToLogin() = runTest {
    val viewModel = AppStartViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(StartDestination.Login, viewModel.destination.value)

    viewModel.onVerificationNeeded()
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
    val viewModel = AppStartViewModel(auth, profiles)
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

  private fun routeOf(
      profile: ProfileResult,
      sessionEmail: String = verified.email,
  ): StartDestination {
    auth.user = verified.copy(email = sessionEmail)
    profiles.getProfileResult = profile
    val viewModel = AppStartViewModel(auth, profiles)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    return viewModel.destination.value
  }

  @Test
  fun anAssociationAnAdminHasntVerified_routesToItsPendingApp() {
    assertEquals(StartDestination.PendingAssociation, routeOf(association(verified = false)))
  }

  @Test
  fun aVerifiedAssociation_routesToTheMainApp_neverToThePendingOne() {
    assertEquals(StartDestination.Main, routeOf(association(verified = true)))
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
    assertEquals(StartDestination.Main, routeOf(association(verified = true)))
  }

  @Test
  fun aPendingAssociationLoggingOut_returnsToLogin_andRefreshRoutesItBackAfterLogIn() {
    auth.user = verified
    profiles.getProfileResult = association(verified = false)
    val viewModel = AppStartViewModel(auth, profiles)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    viewModel.logOut()
    assertEquals(StartDestination.Login, viewModel.destination.value)

    auth.user = verified
    viewModel.refresh()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(StartDestination.PendingAssociation, viewModel.destination.value)
  }

  private val nonEpfl = "club@example.org"

  @Test
  fun aNonEpflAccountWithoutAProfile_routesToError_neverToTheStudentProfileStep() {
    // Without an association profile, a non-EPFL session must not become a student (#208).
    assertEquals(StartDestination.Error, routeOf(ProfileResult.NotFound, sessionEmail = nonEpfl))
  }

  @Test
  fun aNonEpflAccountWithAStudentProfile_routesToError_neverToTheMainApp() {
    assertEquals(StartDestination.Error, routeOf(found, sessionEmail = nonEpfl))
  }

  @Test
  fun aNonEpflPendingAssociation_routesToItsPendingApp() {
    assertEquals(
        StartDestination.PendingAssociation,
        routeOf(association(verified = false), sessionEmail = nonEpfl),
    )
  }

  @Test
  fun anEpflPendingAssociation_routesToItsPendingApp() {
    assertEquals(
        StartDestination.PendingAssociation,
        routeOf(
            association(verified = false, email = "club@epfl.ch"),
            sessionEmail = "club@epfl.ch",
        ),
    )
  }

  @Test
  fun aNonEpflVerifiedAssociation_routesToTheMainApp_until140() {
    assertEquals(
        StartDestination.Main,
        routeOf(association(verified = true), sessionEmail = nonEpfl),
    )
  }

  @Test
  fun anEpflStudent_keepsTheMainAppAndTheProfileStep() {
    assertEquals(StartDestination.Main, routeOf(found, sessionEmail = "test.student@epfl.ch"))
    assertEquals(
        StartDestination.ProfileSetup,
        routeOf(ProfileResult.NotFound, sessionEmail = "test.student@epfl.ch"),
    )
  }

  @Test
  fun lookAlikeEpflDomains_failClosed_withOrWithoutAStudentProfile() {
    // Only an exact @epfl.ch address is a student's: a domain that merely contains or ends with
    // "epfl.ch" must not open the students' app or their profile step (#208).
    for (email in listOf("a@notepfl.ch", "a@epfl.ch.example.org", "a@mail.epfl.ch")) {
      assertEquals(email, StartDestination.Error, routeOf(found, sessionEmail = email))
      assertEquals(
          email,
          StartDestination.Error,
          routeOf(ProfileResult.NotFound, sessionEmail = email),
      )
    }
  }

  @Test
  fun anEpflDomainInCapitals_failsClosed_likeTheRules() {
    // The rules match '@epfl.ch' case-sensitively and would deny every read: fail closed.
    val email = "Test.Student@EPFL.CH"
    assertEquals(StartDestination.Error, routeOf(found, sessionEmail = email))
    assertEquals(StartDestination.Error, routeOf(ProfileResult.NotFound, sessionEmail = email))
  }

  @Test
  fun retryOnTheNonEpflError_readsTheProfileAgain_andStillFailsClosed() {
    auth.user = verified.copy(email = nonEpfl)
    profiles.getProfileResult = ProfileResult.NotFound
    val viewModel = AppStartViewModel(auth, profiles)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(StartDestination.Error, viewModel.destination.value)

    viewModel.refresh()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    viewModel.onProfileCreated()

    // Retry is not a way into the student profile step, nor is a late "profile created".
    assertEquals(StartDestination.Error, viewModel.destination.value)
    assertEquals(2, profiles.getProfileCalls)
  }
}
