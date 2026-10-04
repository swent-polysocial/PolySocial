// Contributors: Claude (shared auth contract for #30, #31 and #32).
package com.polysocial.model.auth

/** The signed-in Firebase user, reduced to what the app needs. */
data class AuthUser(val uid: String, val email: String, val isEmailVerified: Boolean)

/** Outcome of [AuthRepository.signUp]. */
sealed interface SignUpResult {
  data class Success(val user: AuthUser) : SignUpResult

  /** The email is not an `@epfl.ch` address. */
  data object InvalidDomain : SignUpResult

  /** An account with this email already exists. */
  data object EmailAlreadyInUse : SignUpResult

  data object NetworkError : SignUpResult

  data object UnexpectedError : SignUpResult
}

/** Outcome of [AuthRepository.logIn]. */
sealed interface LogInResult {
  data class Success(val user: AuthUser) : LogInResult

  /** The email or the password is wrong (Firebase does not say which). */
  data object WrongCredentials : LogInResult

  data object NetworkError : LogInResult

  data object UnexpectedError : LogInResult
}

/** Outcome of [AuthRepository.sendVerificationEmail]. */
sealed interface SendVerificationResult {
  data object Sent : SendVerificationResult

  /** Firebase refused because too many emails were requested; this is not a real error. */
  data object Throttled : SendVerificationResult

  data object NetworkError : SendVerificationResult

  data object UnexpectedError : SendVerificationResult
}

/** Outcome of [AuthRepository.reloadAndCheckVerified]. */
sealed interface VerificationCheckResult {
  data object Verified : VerificationCheckResult

  data object NotVerified : VerificationCheckResult

  data object NetworkError : VerificationCheckResult

  data object UnexpectedError : VerificationCheckResult
}

/**
 * Account and session operations. The only place that talks to Firebase Authentication; screens and
 * ViewModels depend on this interface so tests can use a fake.
 */
interface AuthRepository {
  /** Creates an account and saves [fullName] as the display name (#30). */
  suspend fun signUp(fullName: String, email: String, password: String): SignUpResult

  /** Signs in with email and password (#32). */
  suspend fun logIn(email: String, password: String): LogInResult

  /** Signs out; the next app start routes to Log in (#32). */
  fun logOut()

  /** The current user, or `null` when nobody is signed in. */
  fun currentUser(): AuthUser?

  /** Sends the verification link to the current user's email (#31). */
  suspend fun sendVerificationEmail(): SendVerificationResult

  /**
   * Reloads the current user, refreshing the ID token so Security Rules see the new
   * `email_verified` value, and reports whether the email is verified (#31).
   */
  suspend fun reloadAndCheckVerified(): VerificationCheckResult
}
