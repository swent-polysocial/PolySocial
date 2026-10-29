// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount client for #206; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.model.account

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
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
 * How long a deletion (sending pending writes, then the call) may take before it counts as a
 * [AccountDeletionResult.NetworkError]: a bit more than the callable's own 70-second client
 * timeout, so the SDK normally answers first.
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
 * The SDK sends the cached ID token and refreshes it only once it has expired (after about an
 * hour), so a retry a few minutes after an attempt whose answer was lost reuses the old token and
 * the function itself finds the account gone. If a refresh does run and Firebase answers that the
 * user no longer exists (`ERROR_USER_NOT_FOUND`), an earlier attempt already deleted the account,
 * so this returns [AccountDeletionResult.Deleted] (deletion policy, #201). On that error, and on a
 * disabled user or a revoked session, the SDK also signs the user out locally, so
 * `FirebaseAuth.currentUser` is null afterwards: the caller captures the uid before starting.
 *
 * Before calling, it waits for Firestore's pending writes
 * ([FirebaseFirestore.waitForPendingWrites]) and returns [AccountDeletionResult.NetworkError]
 * without calling if they can't be sent. An ID token stays valid for up to an hour after the Auth
 * user is deleted, and the rules let an owner create their own `users/{uid}`, so a write queued
 * before the deletion and sent after it would recreate a profile that no account owns.
 *
 * Firebase is resolved on the first call, and [firestore] (the session's database, see
 * `FirestoreSession`) on each call, so constructing this class (for example by Hilt) never touches
 * the SDK. Nothing about the user or the failure is logged.
 */
class FirebaseAccountDeletionRepository(
    authProvider: () -> FirebaseAuth,
    functionsProvider: () -> FirebaseFunctions,
    private val firestore: () -> FirebaseFirestore,
) : AccountDeletionRepository {
  private val auth by lazy(authProvider)
  private val functions by lazy(functionsProvider)

  override suspend fun deleteAccount(): AccountDeletionResult {
    return try {
      if (auth.currentUser == null) return AccountDeletionResult.NotSignedIn
      // Both steps can stay pending forever (offline, or a connection lost mid-answer).
      withTimeout(ACCOUNT_DELETION_TIMEOUT_MILLIS) {
        if (savePendingWrites()) callFunction() else AccountDeletionResult.NetworkError
      }
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

  /**
   * Waits until Firestore has sent this user's queued writes. Returns false if it can't (no
   * connection, or the session's database is blocked by a cache clear), so nothing is deleted.
   */
  private suspend fun savePendingWrites(): Boolean =
      try {
        firestore().waitForPendingWrites().await()
        true
      } catch (_: CancellationException) {
        // Our timeout or the caller's cancellation propagates; a cancelled SDK task is a failure.
        currentCoroutineContext().ensureActive()
        false
      } catch (_: Exception) {
        false
      }

  private suspend fun callFunction(): AccountDeletionResult {
    val answer = functions.getHttpsCallable(DELETE_ACCOUNT_FUNCTION).call().await().getData()
    return if ((answer as? Map<*, *>)?.get("deleted") == true) AccountDeletionResult.Deleted
    else AccountDeletionResult.UnexpectedError
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
   * Maps a failure before the request was sent, mostly refreshing an expired ID token. The SDK
   * wraps it (in an `ExecutionException`), so look through the causes.
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
