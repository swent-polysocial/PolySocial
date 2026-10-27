// Contributors: OpenAI Codex (offline Google authentication and Firebase-managed account regression
// tests and exact-domain validation coverage for #106).
package com.polysocial.model.auth

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class GoogleAuthRepositoryTest {
  private val auth = mockk<FirebaseAuth>()
  private val source = mockk<GoogleCredentialSource>()
  private val context = mockk<Context>()
  private val account = GoogleAccount("student@epfl.ch", "Demo Student")
  private val repository = FirebaseAuthRepository(auth, source)

  @Before
  fun setup() {
    every { auth.signOut() } just runs
    coEvery { source.get(context) } returns GoogleCredential(account, "test-token")
  }

  private fun signedIn(
      email: String? = account.email,
      verified: Boolean = true,
      uid: String = "existing",
  ) {
    val user =
        mockk<FirebaseUser> {
          every { this@mockk.email } returns email
          every { this@mockk.uid } returns uid
          every { isEmailVerified } returns verified
          every { displayName } returns account.displayName
        }
    every { auth.signInWithCredential(any()) } returns
        Tasks.forResult(mockk<AuthResult> { every { this@mockk.user } returns user })
  }

  @Test
  fun verifiedEpflKeepsExistingUidAndName() = runTest {
    signedIn()
    assertEquals(
        GoogleSignInResult.Success(AuthUser("existing", account.email, true, account.displayName)),
        repository.signInWithGoogle(context),
    )
    verify(exactly = 0) {
      auth.signOut()
      auth.createUserWithEmailAndPassword(any(), any())
    }
  }

  @Test
  fun newUserReturnsGoogleName() = runTest {
    signedIn(uid = "new")
    assertEquals(
        GoogleSignInResult.Success(AuthUser("new", account.email, true, account.displayName)),
        repository.signInWithGoogle(context),
    )
  }

  @Test
  fun rejectsNonEpflBeforeCreatingFirebaseAccount() = runTest {
    for (email in
        listOf(
            "student@example.org",
            "student@sub.epfl.ch",
            "student@EPFL.CH",
            "student@epfl.ch.example.org",
            "",
        )) {
      val selected = account.copy(email = email)
      coEvery { source.get(context) } returns GoogleCredential(selected, "test-token")
      assertEquals(GoogleSignInResult.NotEpfl(selected), repository.signInWithGoogle(context))
    }
    verify(exactly = 0) { auth.signInWithCredential(any()) }
    verify(exactly = 5) { auth.signOut() }
  }

  @Test
  fun firebaseEmailIsCheckedAfterTokenValidation() = runTest {
    val invalidEmails =
        listOf("other@example.org", "student@EPFL.CH", "student@sub.epfl.ch", "student@epfl.ch ")
    for (email in invalidEmails) {
      signedIn(email = email)
      assertEquals(GoogleSignInResult.NotEpfl(account), repository.signInWithGoogle(context))
    }
    verify(exactly = invalidEmails.size) { auth.signOut() }
  }

  @Test
  fun missingFirebaseEmailIsRejected() = runTest {
    signedIn(email = null)
    assertEquals(GoogleSignInResult.NotEpfl(account), repository.signInWithGoogle(context))
    verify(exactly = 1) { auth.signOut() }
  }

  @Test
  fun unverifiedFirebaseAccountCannotReachProfileRouting() = runTest {
    signedIn(verified = false)
    assertEquals(GoogleSignInResult.UnexpectedError, repository.signInWithGoogle(context))
    verify(exactly = 1) { auth.signOut() }
  }

  @Test
  fun missingFirebaseUserSignsOut() = runTest {
    every { auth.signInWithCredential(any()) } returns
        Tasks.forResult(mockk<AuthResult> { every { user } returns null })
    assertEquals(GoogleSignInResult.UnexpectedError, repository.signInWithGoogle(context))
    verify(exactly = 1) { auth.signOut() }
  }

  @Test
  fun pickerCancellationHasNoErrorOrFirebaseCall() = runTest {
    coEvery { source.get(context) } throws GetCredentialCancellationException()
    assertEquals(GoogleSignInResult.Cancelled, repository.signInWithGoogle(context))
    verify(exactly = 0) {
      auth.signInWithCredential(any())
      auth.signOut()
    }
  }

  @Test
  fun firebaseFailureIsRecoverableWithoutCreatingAnotherAccount() = runTest {
    every { auth.signInWithCredential(any()) } returns
        Tasks.forException(FirebaseAuthException("ERROR_INTERNAL_ERROR", "test"))
    assertEquals(
        GoogleSignInResult.UnexpectedError,
        repository.signInWithGoogle(context),
    )
    verify(exactly = 1) { auth.signOut() }
    verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
  }

  @Test
  fun networkFailureIsTypedAndSignedOut() = runTest {
    every { auth.signInWithCredential(any()) } returns
        Tasks.forException(FirebaseNetworkException("test"))
    assertEquals(GoogleSignInResult.NetworkError, repository.signInWithGoogle(context))
    verify(exactly = 1) { auth.signOut() }
  }

  @Test
  fun invalidOrUnavailableCredentialIsRecoverable() = runTest {
    coEvery { source.get(context) } throws IllegalStateException("test")
    assertEquals(GoogleSignInResult.UnexpectedError, repository.signInWithGoogle(context))
    verify(exactly = 1) { auth.signOut() }
  }

  @Test
  fun cancelledSdkTaskIsFailureWhenCallerIsActive() = runTest {
    every { auth.signInWithCredential(any()) } returns Tasks.forCanceled()
    assertEquals(GoogleSignInResult.UnexpectedError, repository.signInWithGoogle(context))
  }

  @Test
  fun coroutineCancellationPropagates() = runTest {
    val entered = CompletableDeferred<Unit>()
    coEvery { source.get(context) } coAnswers
        {
          entered.complete(Unit)
          awaitCancellation()
        }
    val attempt = async { repository.signInWithGoogle(context) }
    entered.await()
    attempt.cancel()
    attempt.join()
    assertTrue(attempt.isCancelled)
    verify(exactly = 0) { auth.signOut() }
  }
}
