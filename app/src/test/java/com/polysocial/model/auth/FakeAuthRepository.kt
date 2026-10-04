// Contributors: Claude (test fake for #32); Claude Opus 5.5 (recorded the logIn arguments,
// added the logIn gate).
package com.polysocial.model.auth

import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [AuthRepository] for tests. Set the next result of each operation, read the signed-in
 * user, and check how often each operation was called (e.g. that invalid input never reaches the
 * repository).
 */
class FakeAuthRepository(var user: AuthUser? = null) : AuthRepository {
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

  override fun logOut() {
    logOutCalls++
    user = null
  }

  override fun currentUser(): AuthUser? = user
}
