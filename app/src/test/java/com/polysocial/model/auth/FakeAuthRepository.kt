// Contributors: Claude (test fake shared by #30, #31 and #32); Claude Opus 5.5 (recorded the
// logIn arguments).
package com.polysocial.model.auth

/**
 * In-memory [AuthRepository] for tests. Set the next result of each operation, read the signed-in
 * user, and check how often each operation was called (e.g. that invalid input never reaches the
 * repository).
 */
class FakeAuthRepository(var user: AuthUser? = null) : AuthRepository {
  var signUpResult: SignUpResult = SignUpResult.UnexpectedError
  var logInResult: LogInResult = LogInResult.UnexpectedError
  var sendVerificationResult: SendVerificationResult = SendVerificationResult.Sent
  var verificationCheckResult: VerificationCheckResult = VerificationCheckResult.NotVerified

  var signUpCalls = 0
    private set

  var logInCalls = 0
    private set

  /** The email and password of the last [logIn] call, to check what the caller sends. */
  var lastLogInEmail: String? = null
    private set

  var lastLogInPassword: String? = null
    private set

  var logOutCalls = 0
    private set

  var sendVerificationCalls = 0
    private set

  var verificationCheckCalls = 0
    private set

  override suspend fun signUp(fullName: String, email: String, password: String): SignUpResult {
    signUpCalls++
    return signUpResult.also { if (it is SignUpResult.Success) user = it.user }
  }

  override suspend fun logIn(email: String, password: String): LogInResult {
    logInCalls++
    lastLogInEmail = email
    lastLogInPassword = password
    return logInResult.also { if (it is LogInResult.Success) user = it.user }
  }

  override fun logOut() {
    logOutCalls++
    user = null
  }

  override fun currentUser(): AuthUser? = user

  override suspend fun sendVerificationEmail(): SendVerificationResult {
    sendVerificationCalls++
    return sendVerificationResult
  }

  override suspend fun reloadAndCheckVerified(): VerificationCheckResult {
    verificationCheckCalls++
    return verificationCheckResult.also {
      if (it == VerificationCheckResult.Verified) user = user?.copy(isEmailVerified = true)
    }
  }
}
