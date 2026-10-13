// Contributors: OpenAI Codex (validation, session-neutral recovery and request lifecycle for #164).
package com.polysocial.ui.auth

import com.polysocial.model.auth.*
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PasswordRecoveryViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val user = AuthUser("demo", "student@epfl.ch", false)
  private val auth = FakeAuthRepository(user)
  private val vm = PasswordRecoveryViewModel(auth)

  private fun settle() = main.dispatcher.scheduler.advanceUntilIdle()

  @Test
  fun validationAcceptsAssociationAddressesAndRejectsMalformedInput() {
    for (email in
        listOf(
            "association@example.org",
            "student@epfl.ch",
            " first.last+tag@sub.example.org ",
            "x".repeat(64) + "@example.org",
        )) assertTrue(isRecoveryEmail(email))
    for (email in
        listOf(
            "",
            " ",
            "a@",
            "@example.org",
            "a@@example.org",
            "a b@example.org",
            "a..b@example.org",
            "a@-example.org",
            "a@example-.org",
            "a@localhost",
            "a".repeat(65) + "@example.org",
            "x".repeat(255),
        )) {
      assertFalse(isRecoveryEmail(email))
      vm.editEmail(email)
      vm.submit()
      assertEquals(PasswordResetResult.InvalidEmail, vm.uiState.value.result)
      assertFalse(vm.uiState.value.canSubmit)
    }
    assertEquals(0, auth.resetCalls)
  }

  @Test
  fun pendingRequestLocksEditingReopeningAndDuplicateSubmission() {
    auth.resetGate = CompletableDeferred()
    vm.open(" association@example.org ")
    vm.submit()
    settle()
    assertTrue(vm.uiState.value.loading)
    assertFalse(vm.uiState.value.canSubmit)
    vm.editEmail("other@example.org")
    vm.open("other@example.org")
    vm.submit()
    assertEquals(" association@example.org ", vm.uiState.value.email)
    assertEquals(1, auth.resetCalls)
    assertEquals("association@example.org", auth.lastResetEmail)
    auth.resetGate!!.complete(Unit)
    settle()
    assertEquals(PasswordResetResult.Sent, vm.uiState.value.result)
    vm.submit()
    vm.editEmail("other@example.org")
    assertEquals(1, auth.resetCalls)
    assertEquals(" association@example.org ", vm.uiState.value.email)
    assertEquals(user, auth.currentUser())
    assertEquals(0, auth.logInCalls)
    assertEquals(0, auth.logOutCalls)
    vm.open("fresh@example.org")
    assertEquals(PasswordRecoveryState("fresh@example.org"), vm.uiState.value)
  }

  @Test
  fun failuresKeepEmailAllowRetryAndEditingClearsFeedback() {
    vm.open("student@epfl.ch")
    for (failure in
        listOf(
            PasswordResetResult.NetworkError,
            PasswordResetResult.TooManyRequests,
            PasswordResetResult.UnexpectedError,
        )) {
      auth.resetResult = failure
      vm.submit()
      settle()
      assertEquals(failure, vm.uiState.value.result)
      assertEquals("student@epfl.ch", vm.uiState.value.email)
      assertTrue(vm.uiState.value.canSubmit)
    }
    vm.editEmail("association@example.org")
    assertNull(vm.uiState.value.result)
    auth.resetResult = PasswordResetResult.Sent
    vm.submit()
    settle()
    assertEquals(PasswordResetResult.Sent, vm.uiState.value.result)
    assertEquals(4, auth.resetCalls)
  }

  @Test
  fun unexpectedRepositoryExceptionIsRecoverable() {
    auth.resetException = IllegalStateException("failure")
    vm.open("student@epfl.ch")
    vm.submit()
    settle()
    assertEquals(PasswordResetResult.UnexpectedError, vm.uiState.value.result)
    assertTrue(vm.uiState.value.canSubmit)
    auth.resetException = null
    vm.submit()
    settle()
    assertEquals(PasswordResetResult.Sent, vm.uiState.value.result)
  }

  @Test
  fun clearedScreenCancelsPendingRequestWithoutReportingAnUnexpectedFailure() {
    val store = androidx.lifecycle.ViewModelStore()
    store.put("recovery", vm)
    auth.resetGate = CompletableDeferred()
    vm.open("student@epfl.ch")
    vm.submit()
    settle()
    store.clear()
    settle()
    assertEquals(1, auth.resetCalls)
    assertNull(vm.uiState.value.result)
  }
}
