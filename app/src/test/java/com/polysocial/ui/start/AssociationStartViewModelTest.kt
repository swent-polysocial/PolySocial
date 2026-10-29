// Contributors: OpenAI Codex (restored association startup, marker cleanup and fail-closed
// regressions, #125).
package com.polysocial.ui.start

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.utils.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssociationStartViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  private val user = AuthUser("association-uid", "club@example.org", true)
  private val association =
      Association("association-uid", "Test Club", AssociationType.CLUB, "Campus activities")

  private class Store : AssociationSignupStore {
    var remembered = false
    var readFailure: Exception? = null
    var clearFailure: Exception? = null
    var clears = 0

    override suspend fun remember(email: String) {
      remembered = true
    }

    override suspend fun contains(email: String): Boolean {
      readFailure?.let { throw it }
      return remembered
    }

    override suspend fun clear(email: String) {
      clearFailure?.let { throw it }
      clears++
      remembered = false
    }
  }

  private fun profile(
      email: String = user.email,
      verified: Boolean = false,
      type: AccountType = AccountType.ASSOCIATION,
  ) = ProfileResult.Found(UserProfile(user.uid, email, "Test Club", "", "", type, verified))

  @Test
  fun associationClassificationWinsOverEmailDomainForPendingAndApprovedAccounts() = runTest {
    for (email in listOf("club@example.org", "club@epfl.ch")) {
      for (verified in listOf(false, true)) {
        val store = Store().apply { remembered = true }
        val profiles = FakeUserProfileRepository(profile(email, verified))
        val associations = FakeAssociationRepository(AssociationResult.Found(association))
        val vm =
            AppStartViewModel(
                FakeAuthRepository(user.copy(email = email)),
                profiles,
                store,
                associations,
            )
        advanceUntilIdle()
        assertEquals(
            if (verified) StartDestination.AssociationMain else StartDestination.PendingAssociation,
            vm.destination.value,
        )
        assertEquals(listOf(user.uid), associations.requestedUids)
        assertEquals(1, store.clears)
        assertFalse(store.remembered)
      }
    }
  }

  @Test
  fun persistedAssociationProfileWithoutItsSetupDocumentResumesAssociationSetup() = runTest {
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user),
            FakeUserProfileRepository(profile()),
            Store(),
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.AssociationSetup, vm.destination.value)
  }

  @Test
  fun durableIntentResumesSetupBeforeProfileCreationEvenForAnEpflAssociation() = runTest {
    for (email in listOf("club@example.org", "club@epfl.ch")) {
      val store = Store().apply { remembered = true }
      val profiles = FakeUserProfileRepository()
      val vm =
          AppStartViewModel(
              FakeAuthRepository(user.copy(email = email)),
              profiles,
              store,
              FakeAssociationRepository(),
          )
      advanceUntilIdle()
      assertEquals(StartDestination.AssociationSetup, vm.destination.value)
      assertEquals(0, store.clears)
      assertTrue(profiles.createdProfiles.isEmpty())
    }
  }

  @Test
  fun restoredNonEpflAccountWithoutMarkerResumesSetupWithoutReadingTheStore() = runTest {
    val store = Store().apply { readFailure = IOException("device storage unavailable") }
    val profiles = FakeUserProfileRepository()
    val vm =
        AppStartViewModel(FakeAuthRepository(user), profiles, store, FakeAssociationRepository())
    advanceUntilIdle()
    assertEquals(StartDestination.AssociationSetup, vm.destination.value)
    assertEquals(AccountType.ASSOCIATION, vm.accountType.value)
    assertEquals(0, store.clears)
    assertTrue(profiles.createdProfiles.isEmpty())
  }

  @Test
  fun restoredUnverifiedNonEpflAccountWithoutMarkerUsesAssociationVerification() = runTest {
    val store = Store().apply { readFailure = IOException("device storage unavailable") }
    val profiles = FakeUserProfileRepository()
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user.copy(isEmailVerified = false)),
            profiles,
            store,
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.VerifyEmail, vm.destination.value)
    assertEquals(AccountType.ASSOCIATION, vm.accountType.value)
    assertEquals(0, profiles.getProfileCalls)
    assertEquals(0, store.clears)
  }

  @Test
  fun nonEpflAccountWithStudentProfileCannotEnterTheStudentApp() = runTest {
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user),
            FakeUserProfileRepository(profile(type = AccountType.STUDENT)),
            Store(),
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Error, vm.destination.value)
  }

  @Test
  fun associationReadFailuresAllowRetryWithoutEnteringStudentScreens() = runTest {
    val associations = FakeAssociationRepository()
    for (failure in listOf(AssociationResult.NetworkError, AssociationResult.UnexpectedError)) {
      associations.getAssociationResult = failure
      val vm =
          AppStartViewModel(
              FakeAuthRepository(user),
              FakeUserProfileRepository(profile()),
              Store(),
              associations,
          )
      advanceUntilIdle()
      assertEquals(StartDestination.Error, vm.destination.value)
      associations.getAssociationResult = AssociationResult.Found(association)
      vm.refresh()
      advanceUntilIdle()
      assertEquals(StartDestination.PendingAssociation, vm.destination.value)
    }
  }

  @Test
  fun unexpectedRepositoryExceptionFailsClosed() = runTest {
    val auth = FakeAuthRepository(user)
    val profiles = FakeUserProfileRepository(profile())
    val throwing =
        object : AssociationRepository by FakeAssociationRepository() {
          override suspend fun getAssociation(uid: String): AssociationResult =
              throw IOException("read failed")
        }
    val vm = AppStartViewModel(auth, profiles, Store(), throwing)
    advanceUntilIdle()
    assertEquals(StartDestination.Error, vm.destination.value)
  }

  @Test
  fun profileErrorsAndUnexpectedProfileExceptionRemainRecoverable() = runTest {
    for (failure in listOf(ProfileResult.NetworkError, ProfileResult.UnexpectedError)) {
      val profiles = FakeUserProfileRepository(failure)
      val vm =
          AppStartViewModel(
              FakeAuthRepository(user),
              profiles,
              Store(),
              FakeAssociationRepository(),
          )
      advanceUntilIdle()
      assertEquals(StartDestination.Error, vm.destination.value)
    }
    val throwing =
        object : UserProfileRepository by FakeUserProfileRepository() {
          override suspend fun getProfile(uid: String): ProfileResult =
              throw IOException("read failed")
        }
    val vm =
        AppStartViewModel(FakeAuthRepository(user), throwing, Store(), FakeAssociationRepository())
    advanceUntilIdle()
    assertEquals(StartDestination.Error, vm.destination.value)
  }

  @Test
  fun failedEpflIntentReadDoesNotGuessStudentRoutingAndCanBeRetried() = runTest {
    val store =
        Store().apply {
          remembered = true
          readFailure = IOException("read failed")
        }
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user.copy(email = "club@epfl.ch")),
            FakeUserProfileRepository(),
            store,
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Error, vm.destination.value)
    store.readFailure = null
    vm.refresh()
    advanceUntilIdle()
    assertEquals(StartDestination.AssociationSetup, vm.destination.value)
  }

  @Test
  fun failedMarkerCleanupKeepsPendingAndVerifiedAssociationsAccessibleAndRetriesOnRefresh() =
      runTest {
        for (verified in listOf(false, true)) {
          for (failure in
              listOf(IOException("clear failed"), IllegalStateException("storage failed"))) {
            val store =
                Store().apply {
                  remembered = true
                  clearFailure = failure
                }
            val vm =
                AppStartViewModel(
                    FakeAuthRepository(user),
                    FakeUserProfileRepository(profile(verified = verified)),
                    store,
                    FakeAssociationRepository(AssociationResult.Found(association)),
                )
            advanceUntilIdle()
            val expected =
                if (verified) StartDestination.AssociationMain
                else StartDestination.PendingAssociation
            assertEquals(expected, vm.destination.value)
            assertEquals(SignupMarkerCleanup.RetryNeeded, vm.signupMarkerCleanup.value)
            assertTrue(store.remembered)
            store.clearFailure = null
            vm.refresh()
            advanceUntilIdle()
            assertEquals(expected, vm.destination.value)
            assertEquals(SignupMarkerCleanup.Cleared, vm.signupMarkerCleanup.value)
            assertFalse(store.remembered)
            assertEquals(1, store.clears)
          }
        }
      }

  @Test
  fun cancelledMarkerCleanupPropagatesBeforeReadingAssociationOrRouting() = runTest {
    val store = Store().apply { clearFailure = CancellationException("cancelled clear") }
    val associations = FakeAssociationRepository(AssociationResult.Found(association))
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user),
            FakeUserProfileRepository(profile()),
            store,
            associations,
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Loading, vm.destination.value)
    assertEquals(SignupMarkerCleanup.NotRequired, vm.signupMarkerCleanup.value)
    assertTrue(associations.requestedUids.isEmpty())
    vm.logOut()
    assertEquals(StartDestination.Login, vm.destination.value)
  }

  @Test
  fun logoutCancelsAssociationReadSoLateCompletionCannotReopenTheAccount() = runTest {
    val auth = FakeAuthRepository(user)
    val gate = CompletableDeferred<Unit>()
    val associations =
        FakeAssociationRepository(AssociationResult.Found(association)).apply { this.gate = gate }
    val vm = AppStartViewModel(auth, FakeUserProfileRepository(profile()), Store(), associations)
    runCurrent()
    assertEquals(StartDestination.Loading, vm.destination.value)
    assertEquals(listOf(user.uid), associations.requestedUids)
    vm.logOut()
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(StartDestination.Login, vm.destination.value)
    assertNull(auth.user)
    assertEquals(1, auth.logOutCalls)
  }

  @Test
  fun cancelledIntentReadIsNotConvertedIntoAnUnexpectedFailure() = runTest {
    val store = Store().apply { readFailure = CancellationException("cancelled read") }
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user.copy(email = "club@epfl.ch")),
            FakeUserProfileRepository(),
            store,
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Loading, vm.destination.value)
    vm.logOut()
    assertEquals(StartDestination.Login, vm.destination.value)
  }

  @Test
  fun unverifiedAssociationReturnsToEmailVerificationBeforeAnyProfileRead() = runTest {
    val store = Store().apply { remembered = true }
    val profiles = FakeUserProfileRepository(profile())
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user.copy(isEmailVerified = false)),
            profiles,
            store,
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.VerifyEmail, vm.destination.value)
    assertEquals(AccountType.ASSOCIATION, vm.accountType.value)
    assertEquals(0, profiles.getProfileCalls)
    assertEquals(0, store.clears)
  }

  @Test
  fun processRestartRestoresTheAssociationTypeBeforeEmailVerificationIncludingEpfl() = runTest {
    val store = Store().apply { remembered = true }
    for (email in listOf("club@example.org", "club@epfl.ch")) {
      val auth = FakeAuthRepository(user.copy(email = email, isEmailVerified = false))
      val first =
          AppStartViewModel(auth, FakeUserProfileRepository(), store, FakeAssociationRepository())
      advanceUntilIdle()
      assertEquals(AccountType.ASSOCIATION, first.accountType.value)
      val restarted =
          AppStartViewModel(auth, FakeUserProfileRepository(), store, FakeAssociationRepository())
      advanceUntilIdle()
      assertEquals(StartDestination.VerifyEmail, restarted.destination.value)
      assertEquals(AccountType.ASSOCIATION, restarted.accountType.value)
      assertTrue(store.remembered)
    }
  }

  @Test
  fun unverifiedStudentUsesStudentTypeAndStorageFailureCanRetry() = runTest {
    val store = Store().apply { readFailure = IOException("storage unavailable") }
    val profiles = FakeUserProfileRepository()
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user.copy(email = "student@epfl.ch", isEmailVerified = false)),
            profiles,
            store,
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Error, vm.destination.value)
    assertEquals(0, profiles.getProfileCalls)
    store.readFailure = null
    vm.refresh()
    advanceUntilIdle()
    assertEquals(StartDestination.VerifyEmail, vm.destination.value)
    assertEquals(AccountType.STUDENT, vm.accountType.value)
  }

  @Test
  fun cancelledUnverifiedIntentReadDoesNotEnterVerificationOrReadProtectedProfiles() = runTest {
    val store = Store().apply { readFailure = CancellationException("cancelled read") }
    val profiles = FakeUserProfileRepository()
    val vm =
        AppStartViewModel(
            FakeAuthRepository(user.copy(email = "club@epfl.ch", isEmailVerified = false)),
            profiles,
            store,
            FakeAssociationRepository(),
        )
    advanceUntilIdle()
    assertEquals(StartDestination.Loading, vm.destination.value)
    assertEquals(0, profiles.getProfileCalls)
    vm.logOut()
    assertEquals(StartDestination.Login, vm.destination.value)
  }
}
