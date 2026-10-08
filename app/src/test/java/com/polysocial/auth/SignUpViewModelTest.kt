// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; tested sign-up state and failure paths with
// MockK).
package com.polysocial.auth

import com.polysocial.model.auth.*
import com.polysocial.ui.auth.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var repository: AuthRepository
  private val user = AuthUser("test-uid", "student.test@epfl.ch", false, "Test Student")
  private lateinit var viewModel: SignUpViewModel

  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
    repository = mockk()
    coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.Success(user)
    viewModel = SignUpViewModel(repository)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
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
        coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
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
        coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
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
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
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
        coVerify(exactly = 1) {
          repository.signUp("Test Student", "student.test@epfl.ch", "password1")
        }
        assertEquals(user, signedUp.user)
        assertFalse(signedUp.user.isEmailVerified)
        assertEquals("", viewModel.uiState.value.form.password)
        assertEquals("", viewModel.uiState.value.form.confirmPassword)
        viewModel.signUp()
        viewModel.update(SignUpField.Email, "changed@epfl.ch")
        coVerify(exactly = 1) { repository.signUp(any(), any(), any()) }
        assertEquals(signedUp, viewModel.uiState.value.status)
      }

  @Test
  fun duplicateEmailIsDistinctAndEditingClearsError() =
      runTest(dispatcher) {
        validForm()
        coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.AlreadyInUse
        viewModel.signUp()
        runCurrent()
        assertEquals(SignUpStatus.Error(SignUpResult.AlreadyInUse), viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.canSubmit)
        viewModel.update(SignUpField.Email, "another.test@epfl.ch")
        assertEquals(SignUpStatus.Idle, viewModel.uiState.value.status)
        coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.Success(user)
        viewModel.signUp()
        runCurrent()
        assertTrue(viewModel.uiState.value.status is SignUpStatus.SignedUp)
      }

  @Test
  fun networkFailureCanBeRetried() =
      runTest(dispatcher) {
        validForm()
        coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.NetworkError
        viewModel.signUp()
        runCurrent()
        assertEquals(SignUpStatus.Error(SignUpResult.NetworkError), viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.canSubmit)
        coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.Success(user)
        viewModel.signUp()
        runCurrent()
        assertTrue(viewModel.uiState.value.status is SignUpStatus.SignedUp)
        coVerify(exactly = 2) { repository.signUp(any(), any(), any()) }
      }

  @Test
  fun loadingBlocksRepeatedSubmissionAndFormEdits() =
      runTest(dispatcher) {
        validForm()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.signUp(any(), any(), any()) } coAnswers
            {
              gate.await()
              SignUpResult.Success(user)
            }
        viewModel.signUp()
        runCurrent()
        viewModel.signUp()
        viewModel.update(SignUpField.FullName, "Changed")
        assertEquals(SignUpStatus.Loading, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.canSubmit)
        assertEquals(" Test Student ", viewModel.uiState.value.form.fullName)
        coVerify(exactly = 1) { repository.signUp(any(), any(), any()) }
        gate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.uiState.value.status is SignUpStatus.SignedUp)
      }

  @Test
  fun unexpectedExceptionBecomesSafeError() =
      runTest(dispatcher) {
        validForm()
        coEvery { repository.signUp(any(), any(), any()) } throws
            IllegalStateException("private backend detail")
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
        coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.DisplayNameError
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
        coVerify(exactly = 1) { repository.signUp(any(), any(), any()) }
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
}
