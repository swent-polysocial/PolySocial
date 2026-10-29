// Contributors: OpenAI Codex (distinguish malformed association emails from student domains, #125).
// Contributors: OpenAI Codex (Google sign-in contract for #106; context lifetime wording for #107).
// Contributors: OpenAI Codex (association account type at signup, #125).
// Contributors: Claude (auth contract for #32; Sign up and Verify Email methods left to #30 and
// #31 after review); OpenAI Codex (GPT-6.1 Sol, medium; integrated sign-up results and display
// name; implemented verification result contracts for #31);
// OpenAI Codex (password recovery contract for #164).
// Contributors: Claude (Fable 5.1, Anthropic) drafted the re-authentication contract for #206;
// Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (Opus 5.5, Anthropic) documented the local sign-out on a missing account,
// #206; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.auth

import android.content.Context
import com.polysocial.model.user.AccountType

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

  data object InvalidEmail : Failure

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
 * ViewModels depend on this interface so tests can replace the backend.
 */
interface AuthRepository {
  /**
   * Opens the Google picker using the current activity. The suspend call may hold this context
   * until it returns or is cancelled, including across activity recreation; implementations must
   * not store it beyond the operation.
   */
  suspend fun signInWithGoogle(context: Context): GoogleSignInResult

  /**
   * Creates an Auth account without a Firestore write. Students require EPFL email and save a
   * display name; associations accept a valid email on any domain and skip the personal name.
   */
  suspend fun signUp(
      fullName: String,
      email: String,
      password: String,
      accountType: AccountType = AccountType.STUDENT,
  ): SignUpResult

  /**
   * Requests an account-neutral reset with enumeration protection enabled; never changes the
   * session.
   */
  suspend fun sendPasswordResetEmail(email: String): PasswordResetResult

  /** Signs in with email and password (#32). */
  suspend fun logIn(email: String, password: String): LogInResult

  /** Signs out; the next app start routes to Welcome. */
  fun logOut()

  /** The current user, or `null` when nobody is signed in. */
  fun currentUser(): AuthUser?

  /** Sends a verification link for the signed-in account; preserves Firebase throttling. */
  suspend fun sendVerificationEmail(): SendVerificationResult

  /** Reloads the account and refreshes its ID token before reporting verified access. */
  suspend fun reloadAndCheckVerified(): VerificationResult

  /**
   * How the signed-in account can sign in, to offer the matching re-authentication. Empty when
   * nobody is signed in or no supported method is linked.
   */
  fun currentSignInMethods(): Set<SignInMethod>

  /**
   * Confirms the signed-in user's password before a sensitive operation such as deletion.
   *
   * If the account no longer exists, is disabled or its session was revoked, Firebase signs the
   * user out locally and this returns [ReauthResult.NotSignedIn]; `currentUser()` is null
   * afterwards, so a caller that needs the uid captures it first. During account deletion,
   * `NotSignedIn` after an attempt whose answer was lost means the account is gone: run the same
   * local cleanup as after a deletion.
   */
  suspend fun reauthenticateWithPassword(password: String): ReauthResult

  /**
   * Confirms the signed-in user with the Google picker, like [signInWithGoogle]; the context is
   * held only while the picker is open. A missing, disabled or revoked account signs the user out
   * locally and returns [ReauthResult.NotSignedIn], as for [reauthenticateWithPassword].
   */
  suspend fun reauthenticateWithGoogle(context: Context): ReauthResult
}

/** A sign-in method linked to the account, reduced to the ones the app supports. */
enum class SignInMethod {
  PASSWORD,
  GOOGLE,
}

/** Outcome of re-authenticating the signed-in user; a failure leaves the account unchanged. */
sealed interface ReauthResult {
  data object Success : ReauthResult

  /** The password is wrong. */
  data object WrongPassword : ReauthResult

  /** The user dismissed the Google picker; nothing changed. */
  data object Cancelled : ReauthResult

  /** Nobody is signed in, or the account no longer exists or is disabled. */
  data object NotSignedIn : ReauthResult

  data object NetworkError : ReauthResult

  /** Any other failure, including picking a Google account other than the signed-in one. */
  data object UnexpectedError : ReauthResult
}

/** Outcome of sending an email; a successful send does not mean the account is verified. */
sealed interface SendVerificationResult {
  data object Sent : SendVerificationResult

  data object Throttled : SendVerificationResult

  data object NotSignedIn : SendVerificationResult

  data object NetworkError : SendVerificationResult

  data object UnexpectedError : SendVerificationResult
}

/** Verification is successful only after both account reload and token refresh succeed. */
sealed interface VerificationResult {
  data object Verified : VerificationResult

  data object Unverified : VerificationResult

  data object NotSignedIn : VerificationResult

  data object NetworkError : VerificationResult

  data object UnexpectedError : VerificationResult
}
