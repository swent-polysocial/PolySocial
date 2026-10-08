// Contributors: OpenAI Codex (mocked email delivery, reload and token-refresh regression tests for
// #31).
package com.polysocial.auth

import android.app.Application
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.polysocial.model.auth.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class VerificationRepositoryTest {
  private val auth = mockk<FirebaseAuth>()
  private val user = mockk<FirebaseUser>()
  private val repository = FirebaseAuthRepository(auth)

  private fun signedIn(verified: Boolean = false) {
    every { auth.currentUser } returns user
    every { user.uid } returns "test-uid"
    every { user.isEmailVerified } returns verified
    every { user.reload() } returns Tasks.forResult(null)
    every { user.sendEmailVerification() } returns Tasks.forResult(null)
    every { user.getIdToken(true) } returns Tasks.forResult(mockk<GetTokenResult>())
  }

  @Test
  fun sendsAnEmailWithoutSigningOutOrChangingVerificationState() = runTest {
    signedIn()
    assertEquals(SendVerificationResult.Sent, repository.sendVerificationEmail())
    verify(exactly = 1) { user.sendEmailVerification() }
    verify(exactly = 0) { auth.signOut() }
    verify(exactly = 0) { user.reload() }
  }

  @Test
  fun sendFailuresDistinguishThrottleNetworkAndUnexpected() = runTest {
    signedIn()
    for ((error, result) in
        listOf(
            FirebaseTooManyRequestsException("test failure") to SendVerificationResult.Throttled,
            FirebaseNetworkException("test failure") to SendVerificationResult.NetworkError,
            IllegalStateException("test failure") to SendVerificationResult.UnexpectedError,
        )) {
      every { user.sendEmailVerification() } returns Tasks.forException(error)
      assertEquals(result, repository.sendVerificationEmail())
    }
  }

  @Test
  fun signedOutOperationsDoNotContactFirebase() = runTest {
    every { auth.currentUser } returns null
    assertEquals(SendVerificationResult.NotSignedIn, repository.sendVerificationEmail())
    assertEquals(VerificationResult.NotSignedIn, repository.reloadAndCheckVerified())
    verify(exactly = 0) { user.reload() }
    verify(exactly = 0) { user.sendEmailVerification() }
  }

  @Test
  fun unverifiedReloadDoesNotRefreshTokenOrReportSuccess() = runTest {
    signedIn()
    assertEquals(VerificationResult.Unverified, repository.reloadAndCheckVerified())
    verify(exactly = 1) { user.reload() }
    verify(exactly = 0) { user.getIdToken(any()) }
  }

  @Test
  fun verifiedReloadForcesTokenRefreshBeforeReportingSuccess() = runTest {
    signedIn()
    every { user.reload() } answers
        {
          every { user.isEmailVerified } returns true
          Tasks.forResult(null)
        }
    assertEquals(VerificationResult.Verified, repository.reloadAndCheckVerified())
    verify(exactly = 1) { user.reload() }
    verify(exactly = 1) { user.getIdToken(true) }
  }

  @Test
  fun reloadAndTokenRefreshFailuresDoNotGrantAccess() = runTest {
    for ((error, result) in
        listOf(
            FirebaseNetworkException("test failure") to VerificationResult.NetworkError,
            IllegalStateException("test failure") to VerificationResult.UnexpectedError,
        )) {
      signedIn(true)
      every { user.reload() } returns Tasks.forException(error)
      assertEquals(result, repository.reloadAndCheckVerified())
      every { user.reload() } returns Tasks.forResult(null)
      every { user.getIdToken(true) } returns Tasks.forException(error)
      assertEquals(result, repository.reloadAndCheckVerified())
    }
  }

  @Test
  fun sessionChangedDuringReloadCannotGrantAccessToReplacementUser() = runTest {
    signedIn(true)
    val replacement = mockk<FirebaseUser>()
    every { replacement.uid } returns "other-uid"
    every { user.reload() } answers
        {
          every { auth.currentUser } returns replacement
          Tasks.forResult(null)
        }
    assertEquals(VerificationResult.NotSignedIn, repository.reloadAndCheckVerified())
    verify(exactly = 0) { user.getIdToken(any()) }
  }

  @Test
  fun logoutDuringTokenRefreshCannotGrantAccess() = runTest {
    signedIn(true)
    every { user.getIdToken(true) } answers
        {
          every { auth.currentUser } returns null
          Tasks.forResult(mockk<GetTokenResult>())
        }
    assertEquals(VerificationResult.NotSignedIn, repository.reloadAndCheckVerified())
  }

  @Test
  fun cancellationPropagatesFromAllThreeFirebaseOperations() = runTest {
    signedIn(true)
    every { user.sendEmailVerification() } returns Tasks.forCanceled()
    try {
      repository.sendVerificationEmail()
      fail("send cancellation must propagate")
    } catch (_: CancellationException) {}
    every { user.reload() } returns Tasks.forCanceled()
    try {
      repository.reloadAndCheckVerified()
      fail("reload cancellation must propagate")
    } catch (_: CancellationException) {}
    every { user.reload() } returns Tasks.forResult(null)
    every { user.getIdToken(true) } returns Tasks.forCanceled()
    try {
      repository.reloadAndCheckVerified()
      fail("token cancellation must propagate")
    } catch (_: CancellationException) {}
  }
}
