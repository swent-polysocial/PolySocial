// Contributors: OpenAI Codex (Google sign-in fake for #106).
// Contributors: Claude (test fake for #32); Claude Opus 5.5 (recorded the logIn arguments,
// added the logIn gate); OpenAI Codex (GPT-6.1 Sol, medium; extended the existing fake to
// the sign-up contract with submission controls and added verification controls for #31);
// OpenAI Codex (account-neutral recovery fake for #164).
package com.polysocial.model.auth

import android.content.Context
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [AuthRepository] for tests. Set the next result of each operation, read the signed-in
 * user, and check how often each operation was called (e.g. that invalid input never reaches the
 * repository).
 */
class FakeAuthRepository(var user: AuthUser? = null) : AuthRepository {
  var googleResult: GoogleSignInResult = GoogleSignInResult.UnexpectedError
  var googleGate: CompletableDeferred<Unit>? = null
  var googleCalls = 0
    private set

  override suspend fun signInWithGoogle(context: Context): GoogleSignInResult {
    googleCalls++
    googleGate?.await()
    return googleResult.also { user = (it as? GoogleSignInResult.Success)?.user }
  }

  var signUpResult: SignUpResult = SignUpResult.UnexpectedError

  /** Suspends sign-up until the test releases it, like [logInGate]. */
  var signUpGate: CompletableDeferred<Unit>? = null
  /** Simulates an unexpected repository failure after recording the submission. */
  var signUpException: Exception? = null
  var signUpCalls = 0
    private set

  var lastSignUpFullName: String? = null
    private set

  var lastSignUpEmail: String? = null
    private set

  var lastSignUpPassword: String? = null
    private set

  override suspend fun signUp(fullName: String, email: String, password: String): SignUpResult {
    signUpCalls++
    lastSignUpFullName = fullName
    lastSignUpEmail = email
    lastSignUpPassword = password
    signUpGate?.await()
    signUpException?.let { throw it }
    return signUpResult.also { if (it is SignUpResult.Success) user = it.user }
  }

  var logInResult: LogInResult = LogInResult.UnexpectedError

  /**
   * When set, [logIn] stays suspended until the test completes it, to observe the loading state.
   */
  var logInGate: CompletableDeferred<Unit>? = null

  var logInCalls = 0
    private set

  /** The email and password of the last [logIn] call, to check what the caller sends. */
  var lastLogInEmail: String? = null
    private set

  var lastLogInPassword: String? = null
    private set

  var logOutCalls = 0
    private set

  override suspend fun logIn(email: String, password: String): LogInResult {
    logInCalls++
    lastLogInEmail = email
    lastLogInPassword = password
    logInGate?.await()
    return logInResult.also { if (it is LogInResult.Success) user = it.user }
  }

  var resetResult: PasswordResetResult = PasswordResetResult.Sent
  var resetGate: CompletableDeferred<Unit>? = null
  var resetException: Exception? = null
  var resetCalls = 0
    private set

  var lastResetEmail: String? = null
    private set

  override suspend fun sendPasswordResetEmail(email: String): PasswordResetResult {
    resetCalls++
    lastResetEmail = email
    resetGate?.await()
    resetException?.let { throw it }
    return resetResult
  }

  override fun logOut() {
    logOutCalls++
    user = null
  }

  var sendResult: SendVerificationResult = SendVerificationResult.Sent
  var verificationResult: VerificationResult = VerificationResult.Unverified
  var sendCalls = 0
  var checkCalls = 0
  var sendGate: CompletableDeferred<Unit>? = null
  var checkGate: CompletableDeferred<Unit>? = null

  override suspend fun sendVerificationEmail(): SendVerificationResult {
    sendCalls++
    sendGate?.await()
    return sendResult
  }

  override suspend fun reloadAndCheckVerified(): VerificationResult {
    checkCalls++
    checkGate?.await()
    return verificationResult.also {
      if (it == VerificationResult.Verified) user = user?.copy(isEmailVerified = true)
    }
  }

  override fun currentUser(): AuthUser? = user
}
