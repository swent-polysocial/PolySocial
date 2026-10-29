// Contributors: Claude (Opus 5.5, Anthropic) wrote these re-authentication tests for #206; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.model.auth

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class ReauthenticationTest {
  private val user =
      mockk<FirebaseUser> {
        every { email } returns "student@epfl.ch"
        every { reauthenticate(any()) } returns Tasks.forResult(null)
      }
  private val auth = mockk<FirebaseAuth> { every { currentUser } returns user }
  private val google = mockk<GoogleCredentialSource>()
  private val context = mockk<Context>()
  private val repository = FirebaseAuthRepository(auth, google)

  init {
    coEvery { google.get(context) } returns
        GoogleCredential(GoogleAccount("student@epfl.ch", "Test Student"), "test-token")
  }

  private fun reauthReturns(task: Task<Void>) {
    every { user.reauthenticate(any()) } returns task
  }

  private fun reauthFails(error: Exception) = reauthReturns(Tasks.forException(error))

  private fun providers(vararg ids: String) {
    every { user.providerData } returns
        ids.map { id -> mockk<UserInfo> { every { providerId } returns id } }
  }

  @Test
  fun signInMethods_listOnlyTheSupportedProviders() {
    providers(
        "firebase",
        EmailAuthProvider.PROVIDER_ID,
        GoogleAuthProvider.PROVIDER_ID,
        "github.com",
    )

    assertEquals(
        setOf(SignInMethod.PASSWORD, SignInMethod.GOOGLE),
        repository.currentSignInMethods(),
    )
  }

  @Test
  fun signInMethods_googleOnlyAccountHasNoPassword() {
    providers("firebase", GoogleAuthProvider.PROVIDER_ID)

    assertEquals(setOf(SignInMethod.GOOGLE), repository.currentSignInMethods())
  }

  @Test
  fun signInMethods_nobodySignedIn_isEmpty() {
    every { auth.currentUser } returns null

    assertEquals(emptySet<SignInMethod>(), repository.currentSignInMethods())
  }

  @Test
  fun password_correct_succeedsWithTheAccountsEmailCredential() = runTest {
    val credential = slot<AuthCredential>()
    every { user.reauthenticate(capture(credential)) } returns Tasks.forResult(null)

    assertEquals(ReauthResult.Success, repository.reauthenticateWithPassword("secret"))
    assertEquals(EmailAuthProvider.PROVIDER_ID, credential.captured.provider)
  }

  @Test
  fun password_wrong_returnsWrongPassword() = runTest {
    reauthFails(FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "test"))

    assertEquals(ReauthResult.WrongPassword, repository.reauthenticateWithPassword("wrong"))
  }

  @Test
  fun password_nobodySignedIn_returnsNotSignedInWithoutReauthenticating() = runTest {
    every { auth.currentUser } returns null

    assertEquals(ReauthResult.NotSignedIn, repository.reauthenticateWithPassword("secret"))
    verify(exactly = 0) { user.reauthenticate(any()) }
  }

  @Test
  fun password_accountWithoutEmail_returnsUnexpectedError() = runTest {
    for (email in listOf(null, "")) {
      every { user.email } returns email

      assertEquals(ReauthResult.UnexpectedError, repository.reauthenticateWithPassword("secret"))
    }
    verify(exactly = 0) { user.reauthenticate(any()) }
  }

  @Test
  fun missingOrDisabledAccount_returnsNotSignedIn() = runTest {
    for (code in listOf("ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED")) {
      reauthFails(FirebaseAuthInvalidUserException(code, "test"))

      assertEquals(code, ReauthResult.NotSignedIn, repository.reauthenticateWithPassword("secret"))
    }
  }

  @Test
  fun networkFailure_returnsNetworkError() = runTest {
    reauthFails(FirebaseNetworkException("offline"))

    assertEquals(ReauthResult.NetworkError, repository.reauthenticateWithPassword("secret"))
  }

  @Test
  fun otherFailures_returnUnexpectedError() = runTest {
    for (error in
        listOf(
            FirebaseAuthException("ERROR_TOO_MANY_REQUESTS", "test"),
            IllegalStateException("test"),
        )) {
      reauthFails(error)

      assertEquals(ReauthResult.UnexpectedError, repository.reauthenticateWithPassword("secret"))
    }
  }

  @Test
  fun google_sameAccount_succeedsWithAGoogleCredential() = runTest {
    val credential = slot<AuthCredential>()
    every { user.reauthenticate(capture(credential)) } returns Tasks.forResult(null)

    assertEquals(ReauthResult.Success, repository.reauthenticateWithGoogle(context))
    assertEquals(GoogleAuthProvider.PROVIDER_ID, credential.captured.provider)
  }

  @Test
  fun google_pickerDismissed_returnsCancelledWithoutReauthenticating() = runTest {
    coEvery { google.get(context) } throws GetCredentialCancellationException()

    assertEquals(ReauthResult.Cancelled, repository.reauthenticateWithGoogle(context))
    verify(exactly = 0) { user.reauthenticate(any()) }
  }

  @Test
  fun google_otherAccountPicked_returnsUnexpectedError() = runTest {
    reauthFails(FirebaseAuthInvalidUserException("ERROR_USER_MISMATCH", "test"))

    assertEquals(ReauthResult.UnexpectedError, repository.reauthenticateWithGoogle(context))
  }

  @Test
  fun google_invalidToken_isNotReportedAsWrongPassword() = runTest {
    reauthFails(FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "test"))

    assertEquals(ReauthResult.UnexpectedError, repository.reauthenticateWithGoogle(context))
  }

  @Test
  fun google_nobodySignedIn_returnsNotSignedInWithoutOpeningThePicker() = runTest {
    every { auth.currentUser } returns null

    assertEquals(ReauthResult.NotSignedIn, repository.reauthenticateWithGoogle(context))
    coVerify(exactly = 0) { google.get(any()) }
  }

  @Test
  fun cancelledSdkTaskWhileTheCallerRuns_returnsUnexpectedError() = runTest {
    reauthReturns(Tasks.forCanceled())

    assertEquals(ReauthResult.UnexpectedError, repository.reauthenticateWithPassword("secret"))
  }

  @Test
  fun callerCancellation_propagates() = runTest {
    val pending = TaskCompletionSource<Void>()
    reauthReturns(pending.task)
    val attempt = async { repository.reauthenticateWithPassword("secret") }
    runCurrent()

    attempt.cancel()
    pending.setException(FirebaseNetworkException("late failure"))
    runCurrent()

    assertTrue(attempt.isCancelled)
  }
}
