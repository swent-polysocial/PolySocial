// Contributors: Claude (app start repairs a missing public profile without waiting for it,
// test-first, #291); Claude (testing agent: a new start replaces a running repair, and another
// account's start repairs that account, #291).
package com.polysocial.ui.start

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.EnsurePublicProfileResult
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

/**
 * App start gives a student whose profile was saved before #229 their public profile (#291): it
 * asks [com.polysocial.model.user.UserProfileRepository.ensurePublicProfile] for every student it
 * opens the students' app for, never for anyone else, and never waits for the answer.
 */
class AppStartPublicProfileTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val student =
      UserProfile(
          uid = "u1",
          email = "a@epfl.ch",
          displayName = "Test Student",
          section = "IN",
          year = "BA3",
      )

  private val auth =
      FakeAuthRepository().apply {
        user = AuthUser(uid = "u1", email = "a@epfl.ch", isEmailVerified = true)
      }
  private val profiles = FakeUserProfileRepository(ProfileResult.Found(student))

  private fun start() =
      AppStartViewModel(
          auth,
          profiles,
          associationSignup = EmptyAssociationSignupStore,
          associations =
              FakeAssociationRepository(
                  AssociationResult.Found(Association("u1", "Club", AssociationType.CLUB, "About"))
              ),
          sessions = FakeSessionGuard(),
      )

  @Test
  fun aStudent_opensTheApp_andTheirPublicProfileIsEnsured() = runTest {
    val viewModel = start()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(listOf("u1"), profiles.ensuredUids)
  }

  @Test
  fun aStudent_opensTheApp_withoutWaitingForTheRepair() = runTest {
    // The repair never answers, as on a connection that died silently.
    profiles.ensureGate = CompletableDeferred()

    val viewModel = start()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(listOf("u1"), profiles.ensuredUids)
  }

  @Test
  fun aFailedRepair_stillOpensTheApp() = runTest {
    for (failure in
        listOf(
            EnsurePublicProfileResult.NetworkError,
            EnsurePublicProfileResult.UnexpectedError,
            EnsurePublicProfileResult.NotFound,
        )) {
      profiles.ensurePublicProfileResult = failure

      val viewModel = start()
      advanceUntilIdle()

      assertEquals("after $failure", StartDestination.Main, viewModel.destination.value)
    }
  }

  @Test
  fun anOfflineRepair_isTriedAgainAtTheNextStart() = runTest {
    profiles.ensurePublicProfileResult = EnsurePublicProfileResult.NetworkError
    val viewModel = start()
    advanceUntilIdle()

    // Log in again, or the app starts again.
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(listOf("u1", "u1"), profiles.ensuredUids)
  }

  @Test
  fun logOut_cancelsARepairStillRunning() = runTest {
    profiles.ensureGate = CompletableDeferred()
    val viewModel = start()
    advanceUntilIdle()
    assertEquals(0, profiles.ensureCancellations)

    viewModel.logOut()
    advanceUntilIdle()

    assertEquals(1, profiles.ensureCancellations)
    assertEquals(StartDestination.Login, viewModel.destination.value)
  }

  @Test
  fun aNewStart_replacesARepairStillRunning() = runTest {
    profiles.ensureGate = CompletableDeferred()
    val viewModel = start()
    advanceUntilIdle()

    viewModel.refresh()
    advanceUntilIdle()

    // Only one repair at a time: the first one is cancelled, the second one still waits.
    assertEquals(listOf("u1", "u1"), profiles.ensuredUids)
    assertEquals(1, profiles.ensureCancellations)
    assertEquals(StartDestination.Main, viewModel.destination.value)
  }

  @Test
  fun anotherStudentLoggingIn_afterLogOut_repairsTheirOwnProfile() = runTest {
    profiles.ensureGate = CompletableDeferred()
    val viewModel = start()
    advanceUntilIdle()
    viewModel.logOut()

    auth.user = AuthUser(uid = "u2", email = "b@epfl.ch", isEmailVerified = true)
    profiles.getProfileResult = ProfileResult.Found(student.copy(uid = "u2", email = "b@epfl.ch"))
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(listOf("u1", "u2"), profiles.ensuredUids)
    // Only the first account's repair was cancelled; the second one's is still running.
    assertEquals(1, profiles.ensureCancellations)
  }

  @Test
  fun aPendingOrVerifiedAssociation_isNeverRepaired() = runTest {
    for (verified in listOf(false, true)) {
      profiles.getProfileResult =
          ProfileResult.Found(
              student.copy(
                  email = "club@example.org",
                  accountType = AccountType.ASSOCIATION,
                  isAssociationVerified = verified,
              )
          )
      auth.user = AuthUser(uid = "u1", email = "club@example.org", isEmailVerified = true)

      val viewModel = start()
      advanceUntilIdle()

      assertEquals(
          if (verified) StartDestination.AssociationMain else StartDestination.PendingAssociation,
          viewModel.destination.value,
      )
    }
    assertEquals(emptyList<String>(), profiles.ensuredUids)
  }

  @Test
  fun aNonEpflAccountWithAStudentProfile_isNeverRepaired() = runTest {
    // The rules would refuse it: this session can't use the students' app (#208).
    auth.user = AuthUser(uid = "u1", email = "a@example.org", isEmailVerified = true)

    val viewModel = start()
    advanceUntilIdle()

    assertEquals(StartDestination.Error, viewModel.destination.value)
    assertEquals(emptyList<String>(), profiles.ensuredUids)
  }

  @Test
  fun noProfileYet_isNeverRepaired_theProfileStepWritesBoth() = runTest {
    profiles.getProfileResult = ProfileResult.NotFound

    val viewModel = start()
    advanceUntilIdle()
    viewModel.onProfileCreated()
    advanceUntilIdle()

    assertEquals(StartDestination.Main, viewModel.destination.value)
    assertEquals(emptyList<String>(), profiles.ensuredUids)
  }

  @Test
  fun aFailedProfileRead_isNeverRepaired() = runTest {
    for (failure in listOf(ProfileResult.NetworkError, ProfileResult.UnexpectedError)) {
      profiles.getProfileResult = failure

      val viewModel = start()
      advanceUntilIdle()

      assertEquals(StartDestination.Error, viewModel.destination.value)
    }
    assertEquals(emptyList<String>(), profiles.ensuredUids)
  }

  @Test
  fun signedOutOrUnverified_isNeverRepaired() = runTest {
    for (user in listOf(null, AuthUser(uid = "u1", email = "a@epfl.ch", isEmailVerified = false))) {
      auth.user = user

      start()
      advanceUntilIdle()
    }
    assertEquals(emptyList<String>(), profiles.ensuredUids)
  }
}
