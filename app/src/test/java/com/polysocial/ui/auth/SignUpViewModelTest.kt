// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; tested sign-up state and failure paths with
// the shared fake and MainDispatcherRule; cancellation recovery and cleared-password handoff).
package com.polysocial.ui.auth

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.FieldError
import com.polysocial.model.auth.SignUpField
import com.polysocial.model.auth.SignUpResult
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  private val dispatcher
    get() = mainDispatcherRule.dispatcher

  private lateinit var repository: FakeAuthRepository
  private val user = AuthUser("test-uid", "student.test@epfl.ch", false, "Test Student")
  private lateinit var viewModel: SignUpViewModel

  @Before
  fun setup() {
    repository =
        FakeAuthRepository().apply {
          signUpResult = SignUpResult.Success(this@SignUpViewModelTest.user)
        }
    viewModel = SignUpViewModel(repository)
  }

  private fun validForm() {
    viewModel.update(SignUpField.FullName, " Test Student ")
    viewModel.update(SignUpField.Email, " student.test@epfl.ch ")
    viewModel.update(SignUpField.Password, "password1")
    viewModel.update(SignUpField.ConfirmPassword, "password1")
  }

  @Test
  fun invalidDomainsNeverReachRepository() =
      runTest(dispatcher) {
        validForm()
        listOf(
                "student@example.org",
                "student@epfl.ch.example.org",
                "student@sub.epfl.ch",
                "@epfl.ch",
                "a@@epfl.ch",
                "a b@epfl.ch",
                "a..b@epfl.ch",
            )
            .forEach {
              viewModel.update(SignUpField.Email, it)
              viewModel.signUp()
              runCurrent()
              assertFalse(viewModel.uiState.value.canSubmit)
              assertEquals(
                  FieldError.InvalidDomain,
                  viewModel.uiState.value.visibleError(SignUpField.Email),
              )
            }
        assertEquals(0, repository.signUpCalls)
      }

  @Test
  fun requiredFieldsAndWeakPasswordsBlockSubmission() =
      runTest(dispatcher) {
        viewModel.signUp()
        SignUpField.entries.forEach {
          assertEquals(FieldError.Required, viewModel.uiState.value.visibleError(it))
        }
        SignUpField.entries.forEach { field ->
          validForm()
          viewModel.update(field, "")
          viewModel.signUp()
          assertFalse(viewModel.uiState.value.canSubmit)
        }
        validForm()
        viewModel.update(SignUpField.FullName, "   ")
        viewModel.signUp()
        listOf("short1", "longpassword", "1234567").forEach {
          validForm()
          viewModel.update(SignUpField.Password, it)
          viewModel.update(SignUpField.ConfirmPassword, it)
          viewModel.signUp()
          assertEquals(
              FieldError.PasswordRules,
              viewModel.uiState.value.visibleError(SignUpField.Password),
          )
        }
        runCurrent()
        assertEquals(0, repository.signUpCalls)
      }

  @Test
  fun mismatchAndPasswordEditsRevalidateConfirmation() {
    validForm()
    viewModel.update(SignUpField.ConfirmPassword, "different1")
    assertEquals(
        FieldError.PasswordMismatch,
        viewModel.uiState.value.visibleError(SignUpField.ConfirmPassword),
    )
    assertFalse(viewModel.uiState.value.canSubmit)
    viewModel.update(SignUpField.ConfirmPassword, "password1")
    assertTrue(viewModel.uiState.value.canSubmit)
    viewModel.update(SignUpField.Password, "password2")
    assertEquals(
        FieldError.PasswordMismatch,
        viewModel.uiState.value.visibleError(SignUpField.ConfirmPassword),
    )
    viewModel.signUp()
    assertEquals(0, repository.signUpCalls)
  }

  @Test
  fun successSavesTrimmedDisplayNameAndClearsPasswords() =
      runTest(dispatcher) {
        validForm()
        viewModel.signUp()
        assertEquals(SignUpStatus.Loading, viewModel.uiState.value.status)
        runCurrent()
        val signedUp = viewModel.uiState.value.status as SignUpStatus.SignedUp
        assertEquals("Test Student", signedUp.user.displayName)
        assertEquals(1, repository.signUpCalls)
        assertEquals("Test Student", repository.lastSignUpFullName)
        assertEquals("student.test@epfl.ch", repository.lastSignUpEmail)
        assertEquals("password1", repository.lastSignUpPassword)
        assertEquals(user, signedUp.user)
        assertFalse(signedUp.user.isEmailVerified)
        assertEquals("", viewModel.uiState.value.form.password)
        assertEquals("", viewModel.uiState.value.form.confirmPassword)
        viewModel.signUp()
        viewModel.update(SignUpField.Email, "changed@epfl.ch")
        assertEquals(1, repository.signUpCalls)
        assertEquals(signedUp, viewModel.uiState.value.status)
      }

  @Test
  fun duplicateEmailIsDistinctAndEditingClearsError() =
      runTest(dispatcher) {
        validForm()
        repository.signUpResult = SignUpResult.AlreadyInUse
        viewModel.signUp()
        runCurrent()
        assertEquals(SignUpStatus.Error(SignUpResult.AlreadyInUse), viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.canSubmit)
        viewModel.update(SignUpField.Email, "another.test@epfl.ch")
        assertEquals(SignUpStatus.Idle, viewModel.uiState.value.status)
        repository.signUpResult = SignUpResult.Success(user)
        viewModel.signUp()
        runCurrent()
        assertTrue(viewModel.uiState.value.status is SignUpStatus.SignedUp)
      }

  @Test
  fun networkFailureCanBeRetried() =
      runTest(dispatcher) {
        validForm()
        repository.signUpResult = SignUpResult.NetworkError
        viewModel.signUp()
        runCurrent()
        assertEquals(SignUpStatus.Error(SignUpResult.NetworkError), viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.canSubmit)
        repository.signUpResult = SignUpResult.Success(user)
        viewModel.signUp()
        runCurrent()
        assertTrue(viewModel.uiState.value.status is SignUpStatus.SignedUp)
        assertEquals(2, repository.signUpCalls)
      }

  @Test
  fun loadingBlocksRepeatedSubmissionAndFormEdits() =
      runTest(dispatcher) {
        validForm()
        val gate = CompletableDeferred<Unit>()
        repository.signUpGate = gate
        viewModel.signUp()
        runCurrent()
        viewModel.signUp()
        viewModel.update(SignUpField.FullName, "Changed")
        assertEquals(SignUpStatus.Loading, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.canSubmit)
        assertEquals(" Test Student ", viewModel.uiState.value.form.fullName)
        assertEquals(1, repository.signUpCalls)
        gate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.uiState.value.status is SignUpStatus.SignedUp)
      }

  @Test
  fun unexpectedExceptionBecomesSafeError() =
      runTest(dispatcher) {
        validForm()
        repository.signUpException = IllegalStateException("private backend detail")
        viewModel.signUp()
        runCurrent()
        assertEquals(
            SignUpStatus.Error(SignUpResult.UnexpectedError),
            viewModel.uiState.value.status,
        )
      }

  @Test
  fun displayNameFailureDoesNotOfferAccountCreationAgain() =
      runTest(dispatcher) {
        validForm()
        repository.signUpResult = SignUpResult.DisplayNameError
        viewModel.signUp()
        runCurrent()
        viewModel.update(SignUpField.FullName, "Different Name")
        viewModel.signUp()
        runCurrent()
        assertEquals(
            SignUpStatus.Error(SignUpResult.DisplayNameError),
            viewModel.uiState.value.status,
        )
        assertFalse(viewModel.uiState.value.canSubmit)
        assertEquals(1, repository.signUpCalls)
      }

  @Test
  fun blurShowsRequiredErrorAndPasswordRulesUpdateLive() {
    assertNull(viewModel.uiState.value.visibleError(SignUpField.FullName))
    viewModel.markTouched(SignUpField.FullName)
    assertEquals(FieldError.Required, viewModel.uiState.value.visibleError(SignUpField.FullName))
    viewModel.update(SignUpField.Password, "abcdefgh")
    assertTrue(viewModel.uiState.value.form.hasMinimumLength)
    assertFalse(viewModel.uiState.value.form.hasNumber)
    viewModel.update(SignUpField.Password, "abcdefgh1")
    assertTrue(viewModel.uiState.value.form.hasNumber)
    assertNull(viewModel.uiState.value.visibleError(SignUpField.Password))
  }

  @Test
  fun cancelledSubmissionRestoresIdleAndAllowsRetryWithoutShowingAnError() =
      runTest(dispatcher) {
        validForm()
        val form = viewModel.uiState.value.form
        repository.signUpException = CancellationException("cancelled test request")
        viewModel.signUp()
        runCurrent()
        assertEquals(SignUpStatus.Idle, viewModel.uiState.value.status)
        assertEquals(form, viewModel.uiState.value.form)
        assertTrue(viewModel.uiState.value.canSubmit)
        assertEquals(1, repository.signUpCalls)

        repository.signUpException = null
        viewModel.signUp()
        runCurrent()
        assertEquals(SignUpStatus.SignedUp(user), viewModel.uiState.value.status)
        assertEquals("", viewModel.uiState.value.form.password)
        assertEquals("", viewModel.uiState.value.form.confirmPassword)
        SignUpField.entries.forEach { assertNull(viewModel.uiState.value.visibleError(it)) }
        assertFalse(viewModel.uiState.value.canSubmit)
        assertEquals(2, repository.signUpCalls)
      }
}
