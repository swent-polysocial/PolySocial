// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; implemented framework-free sign-up validation).
package com.polysocial.model.auth

/** Accepts a single mailbox on exactly epfl.ch; subdomains and lookalike suffixes are rejected. */
fun isEpflEmail(email: String): Boolean =
    Regex(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@epfl\\.ch$",
            RegexOption.IGNORE_CASE,
        )
        .matches(email.trim())

enum class SignUpField {
  FullName,
  Email,
  Password,
  ConfirmPassword,
}

enum class FieldError {
  Required,
  InvalidDomain,
  PasswordRules,
  PasswordMismatch,
}

/** Transient form values. Passwords are never saved to persistent or saved-instance state. */
data class SignUpForm(
    val fullName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
) {
  val hasMinimumLength: Boolean
    get() = password.length >= 8

  val hasNumber: Boolean
    get() = password.any { it in '0'..'9' }

  fun error(field: SignUpField): FieldError? =
      when (field) {
        SignUpField.FullName -> if (fullName.isBlank()) FieldError.Required else null
        SignUpField.Email ->
            when {
              email.isBlank() -> FieldError.Required
              !isEpflEmail(email) -> FieldError.InvalidDomain
              else -> null
            }
        SignUpField.Password ->
            when {
              password.isEmpty() -> FieldError.Required
              !hasMinimumLength || !hasNumber -> FieldError.PasswordRules
              else -> null
            }
        SignUpField.ConfirmPassword ->
            when {
              confirmPassword.isEmpty() -> FieldError.Required
              confirmPassword != password -> FieldError.PasswordMismatch
              else -> null
            }
      }

  val isValid: Boolean
    get() = SignUpField.entries.all { error(it) == null }
}
