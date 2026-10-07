// Contributors: Claude (auth contract for #32; Sign up and Verify Email methods left to #30 and
// #31 after review).
package com.polysocial.model.auth

/** The signed-in Firebase user, reduced to what the app needs. */
data class AuthUser(val uid: String, val email: String, val isEmailVerified: Boolean)

/** Outcome of [AuthRepository.logIn]. */
sealed interface LogInResult {
  data class Success(val user: AuthUser) : LogInResult

  /** The email or the password is wrong (Firebase does not say which). */
  data object WrongCredentials : LogInResult

  data object NetworkError : LogInResult

  data object UnexpectedError : LogInResult
}

/**
 * Account and session operations. The only place that talks to Firebase Authentication; screens and
 * ViewModels depend on this interface so tests can use a fake. Sign up (#30) and Verify Email (#31)
 * add their own methods.
 */
interface AuthRepository {
  /** Signs in with email and password (#32). */
  suspend fun logIn(email: String, password: String): LogInResult

  /** Signs out; the next app start routes to Log in (#32). */
  fun logOut()

  /** The current user, or `null` when nobody is signed in. */
  fun currentUser(): AuthUser?
}
