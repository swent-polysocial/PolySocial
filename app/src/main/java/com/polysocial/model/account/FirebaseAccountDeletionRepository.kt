// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount client for #206; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.model.account

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.FirebaseFunctionsException.Code
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * How long a deletion may take before it counts as a [AccountDeletionResult.NetworkError]: a bit
 * more than the callable's own 70-second client timeout, so the SDK normally answers first.
 */
internal const val ACCOUNT_DELETION_TIMEOUT_MILLIS = 80_000L

/**
 * [AccountDeletionRepository] that calls the `deleteAccount` callable Cloud Function
 * ([DELETE_ACCOUNT_FUNCTION] in [ACCOUNT_DELETION_REGION]) as the signed-in user.
 *
 * The function's errors map to results as follows:
 * - `unauthenticated` → [AccountDeletionResult.NotSignedIn];
 * - `failed-precondition` with details `recent-login-required` →
 *   [AccountDeletionResult.RecentLoginRequired], with details `association` →
 *   [AccountDeletionResult.NotAllowed];
 * - `unavailable`, `deadline-exceeded`, no connection, or no answer within
 *   [ACCOUNT_DELETION_TIMEOUT_MILLIS] → [AccountDeletionResult.NetworkError];
 * - anything else (`internal`, `not-found` when the function is not deployed, an unknown answer) →
 *   [AccountDeletionResult.UnexpectedError].
 *
 * Before calling, the SDK refreshes the user's ID token. If Firebase answers that the user no
 * longer exists, an earlier attempt already deleted the account and only its answer was lost, so
 * this returns [AccountDeletionResult.Deleted] (deletion policy, #201).
 *
 * Firebase is resolved on the first call, so constructing this class (for example by Hilt) never
 * touches the SDK. Nothing about the user or the failure is logged.
 */
class FirebaseAccountDeletionRepository(
    authProvider: () -> FirebaseAuth,
    functionsProvider: () -> FirebaseFunctions,
) : AccountDeletionRepository {
  private val auth by lazy(authProvider)
  private val functions by lazy(functionsProvider)

  constructor() :
      this(
          { FirebaseAuth.getInstance() },
          { FirebaseFunctions.getInstance(FirebaseApp.getInstance(), ACCOUNT_DELETION_REGION) },
      )

  override suspend fun deleteAccount(): AccountDeletionResult {
    return try {
      if (auth.currentUser == null) return AccountDeletionResult.NotSignedIn
      // The SDK's task can stay pending forever (a connection lost mid-answer), so bound the wait.
      val answer =
          withTimeout(ACCOUNT_DELETION_TIMEOUT_MILLIS) {
            functions.getHttpsCallable(DELETE_ACCOUNT_FUNCTION).call().await().getData()
          }
      if ((answer as? Map<*, *>)?.get("deleted") == true) AccountDeletionResult.Deleted
      else AccountDeletionResult.UnexpectedError
    } catch (_: TimeoutCancellationException) {
      // Our timeout, unless the caller was cancelled at the same moment.
      currentCoroutineContext().ensureActive()
      AccountDeletionResult.NetworkError
    } catch (_: CancellationException) {
      // A cancelled SDK task is a failure; cancellation of our coroutine must still propagate.
      currentCoroutineContext().ensureActive()
      AccountDeletionResult.UnexpectedError
    } catch (error: FirebaseFunctionsException) {
      functionFailure(error)
    } catch (error: Exception) {
      clientFailure(error)
    }
  }

  /** Maps an error the function (or the SDK's HTTP layer) reported. */
  private fun functionFailure(error: FirebaseFunctionsException): AccountDeletionResult =
      when {
        // The SDK reports a failed connection as INTERNAL with the IOException as its cause.
        error.causes().any { it is IOException } -> AccountDeletionResult.NetworkError
        error.code == Code.UNAUTHENTICATED -> AccountDeletionResult.NotSignedIn
        error.code == Code.FAILED_PRECONDITION ->
            when (error.details) {
              RECENT_LOGIN_REQUIRED -> AccountDeletionResult.RecentLoginRequired
              ASSOCIATION -> AccountDeletionResult.NotAllowed
              else -> AccountDeletionResult.UnexpectedError
            }
        error.code == Code.UNAVAILABLE || error.code == Code.DEADLINE_EXCEEDED ->
            AccountDeletionResult.NetworkError
        else -> AccountDeletionResult.UnexpectedError
      }

  /**
   * Maps a failure before the request was sent, mostly the ID-token refresh. The SDK wraps it (in
   * an `ExecutionException`), so look through the causes.
   */
  private fun clientFailure(error: Exception): AccountDeletionResult {
    val causes = error.causes()
    val invalidUser = causes.filterIsInstance<FirebaseAuthInvalidUserException>().firstOrNull()
    return when {
      // Only a missing user means "already deleted"; a disabled user or a revoked session is not.
      invalidUser != null ->
          if (invalidUser.errorCode == USER_NOT_FOUND) AccountDeletionResult.Deleted
          else AccountDeletionResult.NotSignedIn
      causes.any { it is FirebaseNetworkException || it is IOException } ->
          AccountDeletionResult.NetworkError
      else -> AccountDeletionResult.UnexpectedError
    }
  }

  /** This throwable and its causes, bounded in case of a cause cycle. */
  private fun Throwable.causes(): List<Throwable> =
      generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

  private companion object {
    /** `details` of the function's failed-precondition errors (`deleteAccountHandler.js`). */
    const val RECENT_LOGIN_REQUIRED = "recent-login-required"
    const val ASSOCIATION = "association"

    /** Firebase Auth's error code for a user that no longer exists. */
    const val USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"

    const val MAX_CAUSE_DEPTH = 8
  }
}
