// Contributors: OpenAI Codex (association signup state and durable intent, #125).
// Contributors: OpenAI Codex (discard previous session form state on logout, #109).
// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; implemented sign-up form state and submission;
// reset the form when changing address for #31; ); Claude (fresh form after a sign-out, #74).
package com.polysocial.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FieldError
import com.polysocial.model.auth.SignUpField
import com.polysocial.model.auth.SignUpForm
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.user.AccountType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SignUpStatus {
  data object Idle : SignUpStatus

  data object Loading : SignUpStatus

  data class SignedUp(val user: AuthUser) : SignUpStatus

  data class Error(val reason: SignUpResult.Failure) : SignUpStatus
}

data class SignUpUiState(
    val form: SignUpForm = SignUpForm(),
    val touched: Set<SignUpField> = emptySet(),
    val status: SignUpStatus = SignUpStatus.Idle,
) {
  val canSubmit: Boolean
    get() =
        form.isValid &&
            status !is SignUpStatus.Loading &&
            status !is SignUpStatus.SignedUp &&
            (status as? SignUpStatus.Error)?.reason != SignUpResult.DisplayNameError

  fun visibleError(field: SignUpField): FieldError? =
      if (field in touched && status !is SignUpStatus.SignedUp) form.error(field) else null
}

/** Owns validation, submission and the handoff to Verify Email; it never imports a backend SDK. */
@HiltViewModel
class SignUpViewModel
@Inject
constructor(
    private val repository: AuthRepository,
    private val associationSignup: AssociationSignupStore = EmptyAssociationSignupStore,
) : ViewModel() {
  private val mutableState = MutableStateFlow(SignUpUiState())
  val uiState = mutableState.asStateFlow()

  /**
   * Starts a fresh form: after Verify Email signs out to change the address (#31), and after any
   * sign-out (#74), so a finished sign-up can't send the next person on this device straight to
   * Verify Email.
   */
  fun reset(accountType: AccountType = AccountType.STUDENT) {
    mutableState.value = SignUpUiState(form = SignUpForm(accountType = accountType))
  }

  fun update(field: SignUpField, value: String) {
    val state = mutableState.value
    if (state.status is SignUpStatus.Loading || state.status is SignUpStatus.SignedUp) return
    val form =
        when (field) {
          SignUpField.FullName -> state.form.copy(fullName = value)
          SignUpField.Email -> state.form.copy(email = value)
          SignUpField.Password -> state.form.copy(password = value)
          SignUpField.ConfirmPassword -> state.form.copy(confirmPassword = value)
        }
    mutableState.value =
        state.copy(
            form = form,
            touched = state.touched + field,
            status =
                if ((state.status as? SignUpStatus.Error)?.reason == SignUpResult.DisplayNameError)
                    state.status
                else SignUpStatus.Idle,
        )
  }

  fun markTouched(field: SignUpField) {
    mutableState.value = mutableState.value.copy(touched = mutableState.value.touched + field)
  }

  fun signUp() {
    val state = mutableState.value
    if (!state.canSubmit) {
      if (state.status !is SignUpStatus.Loading && state.status !is SignUpStatus.SignedUp) {
        mutableState.value = state.copy(touched = SignUpField.entries.toSet())
      }
      return
    }
    mutableState.value = state.copy(status = SignUpStatus.Loading)
    viewModelScope.launch {
      val result =
          try {
            if (state.form.accountType == AccountType.ASSOCIATION)
                associationSignup.remember(state.form.email)
            else associationSignup.clear(state.form.email)
            repository.signUp(
                state.form.fullName.trim(),
                state.form.email.trim(),
                state.form.password,
                state.form.accountType,
            )
          } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(status = SignUpStatus.Idle)
            throw cancelled
          } catch (_: Exception) {
            SignUpResult.UnexpectedError
          }
      mutableState.value =
          when (result) {
            is SignUpResult.Success ->
                state.copy(
                    form = state.form.copy(password = "", confirmPassword = ""),
                    status = SignUpStatus.SignedUp(result.user),
                )
            is SignUpResult.Failure -> state.copy(status = SignUpStatus.Error(result))
          }
    }
  }
}
