// Contributors: OpenAI Codex (password recovery validation and outcomes for #164).
package com.polysocial.model.auth

/** Account-neutral outcome; an accepted request does not prove account existence or delivery. */
sealed interface PasswordResetResult {
  /** Firebase accepted the request; show conditional confirmation for every address. */
  data object Sent : PasswordResetResult

  data object InvalidEmail : PasswordResetResult

  data object NetworkError : PasswordResetResult

  data object TooManyRequests : PasswordResetResult

  data object UnexpectedError : PasswordResetResult
}

/** Accepts a mailbox on any domain, including associations; no EPFL-only policy for recovery. */
fun isRecoveryEmail(email: String): Boolean {
  val value = email.trim()
  if (value.length > 254) return false
  val parts = value.split('@')
  if (parts.size != 2 || parts[0].length > 64) return false
  val local = Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*")
  val domain =
      Regex(
          "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+"
      )
  return local.matches(parts[0]) && domain.matches(parts[1])
}
