// Contributors: OpenAI Codex (association signup ordering, retry and cancellation tests, #125).
package com.polysocial.ui.auth

import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.SignUpField
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.user.AccountType
import com.polysocial.utils.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssociationSignUpViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  private val user = AuthUser("association-uid", "club@example.org", false)

  private class Store : AssociationSignupStore {
    val remembered = mutableListOf<String>()
    val cleared = mutableListOf<String>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var clearFailure: Exception? = null

    override suspend fun remember(email: String) {
      gate?.await()
      failure?.let { throw it }
      remembered += email
    }

    override suspend fun contains(email: String) = email in remembered

    override suspend fun clear(email: String) {
      clearFailure?.let { throw it }
      cleared += email
      remembered.remove(email)
    }
  }

  private fun fill(vm: SignUpViewModel, type: AccountType = AccountType.ASSOCIATION) {
    vm.reset(type)
    vm.update(SignUpField.Email, " club@example.org ")
    vm.update(SignUpField.Password, "password1")
    vm.update(SignUpField.ConfirmPassword, "password1")
  }

  @Test
  fun durableIntentIsSavedBeforeTheNetworkCreatesAnAssociation() = runTest {
    val fake =
        FakeAuthRepository().apply {
          signUpResult = SignUpResult.Success(this@AssociationSignUpViewModelTest.user)
        }
    val store = Store()
    val ordered =
        object : AuthRepository by fake {
          override suspend fun signUp(
              fullName: String,
              email: String,
              password: String,
              accountType: AccountType,
          ): SignUpResult {
            assertEquals(listOf(" club@example.org "), store.remembered)
            return fake.signUp(fullName, email, password, accountType)
          }
        }
    val vm = SignUpViewModel(ordered, store)
    fill(vm)
    vm.signUp()
    advanceUntilIdle()
    assertEquals(AccountType.ASSOCIATION, fake.lastSignUpAccountType)
    assertEquals("club@example.org", fake.lastSignUpEmail)
    assertEquals("", fake.lastSignUpFullName)
    assertEquals(SignUpStatus.SignedUp(user), vm.uiState.value.status)
    assertEquals("", vm.uiState.value.form.password)
    assertEquals("", vm.uiState.value.form.confirmPassword)
  }

  @Test
  fun failedIntentPersistencePreventsAccountCreationAndKeepsTheFormForRetry() = runTest {
    val fake =
        FakeAuthRepository().apply {
          signUpResult = SignUpResult.Success(this@AssociationSignUpViewModelTest.user)
        }
    val store = Store().apply { failure = IOException("device storage unavailable") }
    val vm = SignUpViewModel(fake, store)
    fill(vm)
    vm.signUp()
    advanceUntilIdle()
    assertEquals(0, fake.signUpCalls)
    assertEquals(SignUpStatus.Error(SignUpResult.UnexpectedError), vm.uiState.value.status)
    assertEquals("password1", vm.uiState.value.form.password)
    assertTrue(vm.uiState.value.canSubmit)
    store.failure = null
    vm.signUp()
    advanceUntilIdle()
    assertEquals(1, fake.signUpCalls)
    assertEquals(SignUpStatus.SignedUp(user), vm.uiState.value.status)
  }

  @Test
  fun intentPersistenceBlocksDuplicateSubmissionAndCompetingEdits() = runTest {
    val fake =
        FakeAuthRepository().apply {
          signUpResult = SignUpResult.Success(this@AssociationSignUpViewModelTest.user)
        }
    val gate = CompletableDeferred<Unit>()
    val store = Store().apply { this.gate = gate }
    val vm = SignUpViewModel(fake, store)
    fill(vm)
    vm.signUp()
    runCurrent()
    assertEquals(SignUpStatus.Loading, vm.uiState.value.status)
    vm.update(SignUpField.Email, "other@example.org")
    vm.signUp()
    assertEquals(" club@example.org ", vm.uiState.value.form.email)
    assertEquals(0, fake.signUpCalls)
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(1, fake.signUpCalls)
    assertEquals(1, store.remembered.size)
  }

  @Test
  fun cancellationDuringIntentWriteRestoresIdleWithoutCallingAuth() = runTest {
    val fake = FakeAuthRepository()
    val store = Store().apply { failure = CancellationException("test cancellation") }
    val vm = SignUpViewModel(fake, store)
    fill(vm)
    vm.signUp()
    advanceUntilIdle()
    assertEquals(SignUpStatus.Idle, vm.uiState.value.status)
    assertEquals(0, fake.signUpCalls)
    assertTrue(vm.uiState.value.canSubmit)
  }

  @Test
  fun backendFailurePreservesTheDurableAssociationIntentAndForm() = runTest {
    val fake = FakeAuthRepository().apply { signUpResult = SignUpResult.NetworkError }
    val store = Store()
    val vm = SignUpViewModel(fake, store)
    fill(vm)
    vm.signUp()
    advanceUntilIdle()
    assertEquals(SignUpStatus.Error(SignUpResult.NetworkError), vm.uiState.value.status)
    assertEquals(listOf(" club@example.org "), store.remembered)
    assertEquals(AccountType.ASSOCIATION, vm.uiState.value.form.accountType)
    assertEquals("password1", vm.uiState.value.form.password)
  }

  @Test
  fun studentSignupClearsStaleAssociationIntentBeforeCreatingTheAccount() = runTest {
    val store = Store().apply { remembered += "student@epfl.ch" }
    val fake =
        FakeAuthRepository().apply {
          signUpResult =
              SignUpResult.Success(
                  this@AssociationSignUpViewModelTest.user.copy(email = "student@epfl.ch")
              )
        }
    val ordered =
        object : AuthRepository by fake {
          override suspend fun signUp(
              fullName: String,
              email: String,
              password: String,
              accountType: AccountType,
          ): SignUpResult {
            assertTrue(store.remembered.isEmpty())
            assertEquals(listOf("student@epfl.ch"), store.cleared)
            return fake.signUp(fullName, email, password, accountType)
          }
        }
    val vm = SignUpViewModel(ordered, store)
    fill(vm, AccountType.STUDENT)
    vm.update(SignUpField.FullName, "Test Student")
    vm.update(SignUpField.Email, "student@epfl.ch")
    vm.signUp()
    advanceUntilIdle()
    assertEquals(
        SignUpStatus.SignedUp(user.copy(email = "student@epfl.ch")),
        vm.uiState.value.status,
    )
    assertEquals(AccountType.STUDENT, fake.lastSignUpAccountType)
    assertEquals(1, fake.signUpCalls)
  }

  @Test
  fun failedStaleIntentClearCannotCreateAStudentThatWouldResumeAsAnAssociation() = runTest {
    val store =
        Store().apply {
          remembered += "student@epfl.ch"
          clearFailure = IOException("storage unavailable")
        }
    val fake = FakeAuthRepository()
    val vm = SignUpViewModel(fake, store)
    fill(vm, AccountType.STUDENT)
    vm.update(SignUpField.FullName, "Test Student")
    vm.update(SignUpField.Email, "student@epfl.ch")
    vm.signUp()
    advanceUntilIdle()
    assertEquals(0, fake.signUpCalls)
    assertEquals(listOf("student@epfl.ch"), store.remembered)
    assertEquals(SignUpStatus.Error(SignUpResult.UnexpectedError), vm.uiState.value.status)
    assertEquals("password1", vm.uiState.value.form.password)
    assertTrue(vm.uiState.value.canSubmit)
  }

  @Test
  fun studentSignupNeverWritesAssociationIntentAndResetRestoresTheDefaultType() = runTest {
    val fake =
        FakeAuthRepository().apply {
          signUpResult =
              SignUpResult.Success(
                  this@AssociationSignUpViewModelTest.user.copy(email = "student@epfl.ch")
              )
        }
    val store = Store().apply { clearFailure = IOException("storage unavailable") }
    val vm = SignUpViewModel(fake, store)
    fill(vm, AccountType.STUDENT)
    vm.update(SignUpField.FullName, "Test Student")
    vm.update(SignUpField.Email, "student@epfl.ch")
    vm.signUp()
    advanceUntilIdle()
    assertEquals(AccountType.STUDENT, fake.lastSignUpAccountType)
    assertTrue(store.remembered.isEmpty())
    assertTrue(store.cleared.isEmpty())
    assertEquals(1, fake.signUpCalls)
    assertTrue(vm.uiState.value.status is SignUpStatus.SignedUp)
    vm.reset(AccountType.ASSOCIATION)
    assertEquals(AccountType.ASSOCIATION, vm.uiState.value.form.accountType)
    vm.reset()
    assertEquals(AccountType.STUDENT, vm.uiState.value.form.accountType)
    assertEquals("", vm.uiState.value.form.email)
    assertEquals(SignUpStatus.Idle, vm.uiState.value.status)
  }
}
