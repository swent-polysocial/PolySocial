// Contributors: Claude Opus 5.5 (wrote these tests); OpenAI Codex
// (GPT-6.1 Sol, medium; adapted display-name fixtures and consolidated sign-up adapter tests).
package com.polysocial.model.auth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import io.mockk.Called
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class FirebaseAuthRepositoryTest {
  private val auth = mockk<FirebaseAuth>()
  private val repository = FirebaseAuthRepository(auth)

  private fun firebaseUser(uid: String = "u1", email: String? = "a@epfl.ch", verified: Boolean) =
      mockk<FirebaseUser> {
        every { this@mockk.uid } returns uid
        every { this@mockk.email } returns email
        every { isEmailVerified } returns verified
        every { displayName } returns null
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

  private lateinit var user: FirebaseUser
  private lateinit var account: AuthResult
  private val email = "student.test@epfl.ch"

  @Before
  fun setup() {
    user = mockk()
    account = mockk()
    every { auth.currentUser } returns null
    every { account.user } returns null
  }

  private fun accountCreated() {
    every { account.user } returns user
    every { user.uid } returns "test-uid"
    every { user.email } returns email
    every { user.displayName } returns "Test Student"
    every { user.isEmailVerified } returns false
    every { auth.createUserWithEmailAndPassword(email, "password1") } returns
        Tasks.forResult(account)
  }

  @Test
  fun successWaitsForDisplayNameUpdateAndUsesFullName() = runTest {
    accountCreated()
    val profileTask = TaskCompletionSource<Void>()
    every { user.updateProfile(any()) } returns profileTask.task
    val result = async { repository.signUp(" Test Student ", " $email ", "password1") }
    runCurrent()
    assertFalse(result.isCompleted)
    val profile = slot<UserProfileChangeRequest>()
    verify(exactly = 1) { user.updateProfile(capture(profile)) }
    assertEquals("Test Student", profile.captured.displayName)
    profileTask.setResult(null)
    val success = result.await() as SignUpResult.Success
    assertEquals("Test Student", success.user.displayName)
    assertEquals(email, success.user.email)
    assertFalse(success.user.isEmailVerified)
    verify(exactly = 1) { auth.createUserWithEmailAndPassword(email, "password1") }
    verify(exactly = 0) { user.sendEmailVerification() }
  }

  @Test
  fun createdAccountUsesTheSameRepositoryForLoginAndLogout() = runTest {
    accountCreated()
    every { user.updateProfile(any()) } returns Tasks.forResult(null)
    every { auth.currentUser } returns user
    every { auth.signOut() } answers { every { auth.currentUser } returns null }
    every { auth.signInWithEmailAndPassword(email, "password1") } answers
        {
          every { auth.currentUser } returns user
          Tasks.forResult(account)
        }

    val created = repository.signUp("Test Student", email, "password1") as SignUpResult.Success
    assertEquals(created.user, repository.currentUser())
    repository.logOut()
    assertNull(repository.currentUser())
    assertEquals(LogInResult.Success(created.user), repository.logIn(email, "password1"))
    verify(exactly = 1) { auth.signOut() }
    verify(exactly = 1) { auth.signInWithEmailAndPassword(email, "password1") }
  }

  @Test
  fun invalidDomainDoesNotContactSdk() = runTest {
    assertEquals(
        SignUpResult.InvalidDomain,
        repository.signUp("Test Student", "student@example.org", "password1"),
    )
    verify { auth wasNot Called }
  }

  @Test
  fun failuresMapToDistinctSafeResults() = runTest {
    val failures =
        listOf(
            FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "test failure") to
                SignUpResult.AlreadyInUse,
            FirebaseNetworkException("test failure") to SignUpResult.NetworkError,
            FirebaseAuthWeakPasswordException(
                "ERROR_WEAK_PASSWORD",
                "test failure",
                "test reason",
            ) to SignUpResult.InvalidPassword,
            FirebaseTooManyRequestsException("test failure") to SignUpResult.TooManyRequests,
            IllegalStateException("backend details must stay private") to
                SignUpResult.UnexpectedError,
        )
    for ((error, expected) in failures) {
      every { auth.createUserWithEmailAndPassword(email, "password1") } returns
          Tasks.forException(error)
      assertEquals(expected, repository.signUp("Test Student", email, "password1"))
    }
  }

  @Test
  fun displayNameFailureReportsPartialAccountCreation() = runTest {
    accountCreated()
    every { user.updateProfile(any()) } returns
        Tasks.forException(FirebaseNetworkException("test failure"))
    assertEquals(
        SignUpResult.DisplayNameError,
        repository.signUp("Test Student", email, "password1"),
    )
    verify(exactly = 0) { user.sendEmailVerification() }
  }

  @Test
  fun missingCreatedUserDoesNotProduceFalseSuccess() = runTest {
    every { auth.createUserWithEmailAndPassword(email, "password1") } returns
        Tasks.forResult(account)
    assertEquals(
        SignUpResult.UnexpectedError,
        repository.signUp("Test Student", email, "password1"),
    )
    verify { user wasNot Called }
  }

  @Test
  fun cancelledAccountCreationIsNotReportedAsNetworkOrUnknownError() = runTest {
    every { auth.createUserWithEmailAndPassword(email, "password1") } returns Tasks.forCanceled()
    try {
      repository.signUp("Test Student", email, "password1")
      fail("Cancellation must propagate")
    } catch (_: kotlinx.coroutines.CancellationException) {
      verify { user wasNot Called }
    }
  }

  @Test
  fun cancelledDisplayNameUpdateIsNotReportedAsPartialFailure() = runTest {
    accountCreated()
    every { user.updateProfile(any()) } returns Tasks.forCanceled()
    var cancellationPropagated = false
    try {
      repository.signUp("Test Student", email, "password1")
    } catch (_: kotlinx.coroutines.CancellationException) {
      cancellationPropagated = true
    }
    assertTrue("Cancellation must propagate", cancellationPropagated)
    verify(exactly = 0) { user.sendEmailVerification() }
  }

  @Test
  fun currentUserReflectsSessionAndDoesNotVerifyIt() {
    assertNull(repository.currentUser())
    accountCreated()
    every { auth.currentUser } returns user
    assertEquals(AuthUser("test-uid", email, false, "Test Student"), repository.currentUser())
    verify(exactly = 0) { user.sendEmailVerification() }
  }
}
