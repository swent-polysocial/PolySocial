// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial.model.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseAuthRepositoryTest {
  private val auth = mockk<FirebaseAuth>()
  private val repository = FirebaseAuthRepository(auth)

  private fun firebaseUser(uid: String = "u1", email: String? = "a@epfl.ch", verified: Boolean) =
      mockk<FirebaseUser> {
        every { this@mockk.uid } returns uid
        every { this@mockk.email } returns email
        every { isEmailVerified } returns verified
      }

  private fun signInReturns(task: Task<AuthResult>) {
    every { auth.signInWithEmailAndPassword(any(), any()) } returns task
  }

  private fun signInSucceeds(user: FirebaseUser?) {
    signInReturns(Tasks.forResult(mockk()))
    every { auth.currentUser } returns user
  }

  private fun signInFails(error: Exception) {
    signInReturns(Tasks.forException(error))
  }

  private suspend fun logIn(email: String = "a@epfl.ch", password: String = "secret"): LogInResult =
      repository.logIn(email, password)

  @Test
  fun logIn_verifiedUser_returnsSuccessWithTheUser() = runTest {
    signInSucceeds(firebaseUser(verified = true))

    assertEquals(LogInResult.Success(AuthUser("u1", "a@epfl.ch", true)), logIn())
  }

  @Test
  fun logIn_unverifiedUser_returnsSuccessWithUnverifiedEmail() = runTest {
    signInSucceeds(firebaseUser(verified = false))

    assertEquals(LogInResult.Success(AuthUser("u1", "a@epfl.ch", false)), logIn())
  }

  @Test
  fun logIn_successButNoCurrentUser_returnsUnexpectedError() = runTest {
    signInSucceeds(user = null)

    assertEquals(LogInResult.UnexpectedError, logIn())
  }

  @Test
  fun logIn_invalidCredentials_returnsWrongCredentials() = runTest {
    signInFails(FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "wrong"))

    assertEquals(LogInResult.WrongCredentials, logIn())
  }

  @Test
  fun logIn_unknownUser_returnsWrongCredentials() = runTest {
    signInFails(FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "no user"))

    assertEquals(LogInResult.WrongCredentials, logIn())
  }

  @Test
  fun logIn_networkFailure_returnsNetworkError() = runTest {
    signInFails(FirebaseNetworkException("offline"))

    assertEquals(LogInResult.NetworkError, logIn())
  }

  @Test
  fun logIn_otherFirebaseFailure_returnsUnexpectedError() = runTest {
    signInFails(FirebaseTooManyRequestsException("too many"))

    assertEquals(LogInResult.UnexpectedError, logIn())
  }

  @Test
  fun logIn_passesEmailAndPasswordUnchanged() = runTest {
    signInSucceeds(firebaseUser(verified = true))

    logIn(email = " A@epfl.ch ", password = " pw ")

    verify(exactly = 1) { auth.signInWithEmailAndPassword(" A@epfl.ch ", " pw ") }
  }

  @Test
  fun logOut_signsOutOnce() {
    every { auth.signOut() } just runs

    repository.logOut()

    verify(exactly = 1) { auth.signOut() }
  }

  @Test
  fun currentUser_mapsTheFirebaseUser() {
    every { auth.currentUser } returns firebaseUser(uid = "u2", verified = true)

    assertEquals(AuthUser("u2", "a@epfl.ch", true), repository.currentUser())
  }

  @Test
  fun currentUser_withoutEmail_usesAnEmptyEmail() {
    every { auth.currentUser } returns firebaseUser(email = null, verified = false)

    assertEquals(AuthUser("u1", "", false), repository.currentUser())
  }

  @Test
  fun currentUser_signedOut_isNull() {
    every { auth.currentUser } returns null

    assertNull(repository.currentUser())
  }
}
