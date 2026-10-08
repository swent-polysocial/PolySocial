// Contributors: Claude (auth contract for #32; Sign up and Verify Email methods left to #30 and
// #31 after review); OpenAI Codex (GPT-6.1 Sol, medium; integrated sign-up results and display
// name).
package com.polysocial.model.auth

/** The signed-in Firebase user, reduced to what the app needs. */
data class AuthUser(
    val uid: String,
    val email: String,
    val isEmailVerified: Boolean,
    val displayName: String? = null,
)

/** Outcome of [AuthRepository.logIn]. */
sealed interface LogInResult {
  data class Success(val user: AuthUser) : LogInResult

  /** The email or the password is wrong (Firebase does not say which). */
  data object WrongCredentials : LogInResult

  data object NetworkError : LogInResult

  data object UnexpectedError : LogInResult
}

/** Outcome of account creation; backend details never become UI messages. */
sealed interface SignUpResult {
  sealed interface Failure : SignUpResult

  data class Success(val user: AuthUser) : SignUpResult

  data object InvalidDomain : Failure

  data object AlreadyInUse : Failure

  data object NetworkError : Failure

  data object InvalidPassword : Failure

  data object TooManyRequests : Failure

  data object UnexpectedError : Failure

  /** The account exists, but saving its display name failed; do not create it again. */
  data object DisplayNameError : Failure
}

/**
 * Account and session operations. The only place that talks to Firebase Authentication; screens and
 * ViewModels depend on this interface so tests can replace the backend. Verify Email (#31) adds its
 * own methods.
 */
interface AuthRepository {
  /**
   * Creates an account and saves its display name without verification or a Firestore write (#30).
   */
  suspend fun signUp(fullName: String, email: String, password: String): SignUpResult

  /** Signs in with email and password (#32). */
  suspend fun logIn(email: String, password: String): LogInResult

  /** Signs out; the next app start routes to Log in (#32). */
  fun logOut()

  /** The current user, or `null` when nobody is signed in. */
  fun currentUser(): AuthUser?
}
