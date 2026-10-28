// Contributors: OpenAI Codex (association Firebase sign-up boundaries and failure tests, #125).
package com.polysocial.model.auth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.polysocial.model.user.AccountType
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class AssociationFirebaseSignUpTest {
  private val auth = mockk<FirebaseAuth>()
  private val repository = FirebaseAuthRepository(auth)

  private fun account(email: String): FirebaseUser {
    val user = mockk<FirebaseUser>()
    every { user.uid } returns "association-test"
    every { user.email } returns email
    every { user.isEmailVerified } returns false
    every { user.displayName } returns null
    val result = mockk<AuthResult>()
    every { result.user } returns user
    every { auth.createUserWithEmailAndPassword(email, "password1") } returns
        Tasks.forResult(result)
    return user
  }

  @Test
  fun nonEpflAssociationCreatesAnAccountWithoutWritingAStudentDisplayName() = runTest {
    val user = account("club@example.org")
    val result = repository.signUp("", " club@example.org ", "password1", AccountType.ASSOCIATION)

    assertEquals(
        SignUpResult.Success(AuthUser("association-test", "club@example.org", false)),
        result,
    )
    assertFalse((result as SignUpResult.Success).user.isEmailVerified)
    verify(exactly = 1) { auth.createUserWithEmailAndPassword("club@example.org", "password1") }
    verify(exactly = 0) { user.updateProfile(any()) }
    verify(exactly = 0) { user.sendEmailVerification() }
  }

  @Test
  fun epflAssociationStillSkipsTheStudentDisplayNameStep() = runTest {
    val user = account("club.test@epfl.ch")

    assertEquals(
        SignUpResult.Success(AuthUser("association-test", "club.test@epfl.ch", false)),
        repository.signUp("", "club.test@epfl.ch", "password1", AccountType.ASSOCIATION),
    )
    verify(exactly = 0) { user.updateProfile(any()) }
  }

  @Test
  fun ordinaryStudentStillCannotCreateANonEpflAccount() = runTest {
    assertEquals(
        SignUpResult.InvalidDomain,
        repository.signUp(
            "Example Student",
            "student@example.org",
            "password1",
            AccountType.STUDENT,
        ),
    )
    verify { auth wasNot Called }
  }

  @Test
  fun malformedAssociationEmailsAreRefusedBeforeTheSdkIsCalled() = runTest {
    for (email in listOf("", "club.example.org", "club@", "@example.org", "club @example.org")) {
      assertEquals(
          email,
          SignUpResult.InvalidDomain,
          repository.signUp("", email, "password1", AccountType.ASSOCIATION),
      )
    }
    verify { auth wasNot Called }
  }

  @Test
  fun associationBackendFailuresUseTheExistingTypedResults() = runTest {
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
            IllegalStateException("test failure") to SignUpResult.UnexpectedError,
        )
    for ((failure, expected) in failures) {
      every { auth.createUserWithEmailAndPassword("club@example.org", "password1") } returns
          Tasks.forException(failure)
      assertEquals(
          expected,
          repository.signUp("", "club@example.org", "password1", AccountType.ASSOCIATION),
      )
    }
  }

  @Test
  fun noCreatedUserNeverReportsSuccess() = runTest {
    val result = mockk<AuthResult>()
    every { result.user } returns null
    every { auth.createUserWithEmailAndPassword(any(), any()) } returns Tasks.forResult(result)

    assertEquals(
        SignUpResult.UnexpectedError,
        repository.signUp("", "club@example.org", "password1", AccountType.ASSOCIATION),
    )
  }

  @Test
  fun associationSignupCancellationPropagates() = runTest {
    every { auth.createUserWithEmailAndPassword(any(), any()) } returns Tasks.forCanceled()
    try {
      repository.signUp("", "club@example.org", "password1", AccountType.ASSOCIATION)
      fail("Cancelled account creation must not report a recoverable backend error")
    } catch (_: CancellationException) {
      verify(exactly = 1) { auth.createUserWithEmailAndPassword("club@example.org", "password1") }
    }
  }
}
