// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount client for #206, then moved
// it from the Functions SDK to a plain HTTPS call; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.account

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * How long a deletion may take before it counts as a [AccountDeletionResult.NetworkError]. The
 * budget covers waiting for pending writes, fetching the ID token and the request. The request
 * itself is bounded by [CONNECT_TIMEOUT_MILLIS] and [READ_TIMEOUT_MILLIS] (70 s together), and a
 * blocking read only notices this bound once it returns, so a slow write flush can stretch a
 * deletion past 80 s, never past 150 s. If this bound ends while the request is in flight, the
 * server may still delete the account: that is a lost answer, which a retry covers (both server
 * steps are idempotent).
 */
internal const val ACCOUNT_DELETION_TIMEOUT_MILLIS = 80_000L

/** How long the request may take to connect to the function. */
internal const val CONNECT_TIMEOUT_MILLIS = 10_000

/** How long the request may wait for the function's answer once connected. */
internal const val READ_TIMEOUT_MILLIS = 60_000

/**
 * [AccountDeletionRepository] that calls the `deleteAccount` callable Cloud Function
 * ([DELETE_ACCOUNT_FUNCTION] in [ACCOUNT_DELETION_REGION]) as the signed-in user, over plain HTTPS
 * with the callable protocol: `POST {"data":{}}` with the user's ID token as a bearer token. It
 * does not use the Functions SDK, which pulls in firebase-iid and registers an FCM token on every
 * call (#269). [parseDeleteAccountResponse] maps the answer; no connection, a timeout or an
 * unreachable host is [AccountDeletionResult.NetworkError].
 *
 * Before calling, it waits for Firestore's pending writes
 * ([FirebaseFirestore.waitForPendingWrites]) and returns [AccountDeletionResult.NetworkError]
 * without calling if they can't be sent ([AccountDeletionResult.UnexpectedError] if the session's
 * database is blocked by a cache clear). An ID token stays valid for up to an hour after the Auth
 * user is deleted, and the rules let an owner create their own `users/{uid}`, so a write queued
 * before the deletion and sent after it would recreate a profile that no account owns.
 *
 * The ID token comes from `getIdToken(false)`: the cached token, refreshed only once it has expired
 * (after about an hour). A retry a few minutes after an attempt whose answer was lost therefore
 * reuses the old token, and the function itself finds the account gone. If a refresh does run and
 * Firebase answers that the user no longer exists (`ERROR_USER_NOT_FOUND`), an earlier attempt
 * already deleted the account, so this returns [AccountDeletionResult.Deleted] (deletion
 * policy, #201); a disabled user or a revoked session is [AccountDeletionResult.NotSignedIn]. On
 * those errors Firebase also signs the user out locally, so `FirebaseAuth.currentUser` is null
 * afterwards: the caller captures the uid before starting.
 *
 * Firebase is resolved on the first call, and [firestore] (the session's database, see
 * `FirestoreSession`) on each call, so constructing this class (for example by Hilt) never touches
 * the SDK. Nothing about the user, the token or the failure is logged.
 *
 * @param baseUrl where the functions are served, without the function name: by default
 *   `https://<region>-<project>.cloudfunctions.net` for the app's Firebase project; the Functions
 *   emulator would be `http://10.0.2.2:5001/<project>/<region>`.
 * @param ioDispatcher runs the blocking HTTP call off the main thread.
 * @param readTimeoutMillis how long to wait for the answer once connected, so tests can shorten it.
 */
class FirebaseAccountDeletionRepository(
    authProvider: () -> FirebaseAuth,
    private val firestore: () -> FirebaseFirestore,
    baseUrl: () -> String = { deleteAccountBaseUrl(FirebaseApp.getInstance().options.projectId) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : AccountDeletionRepository {
  private val auth by lazy(authProvider)
  private val endpoint by lazy { "${baseUrl()}/$DELETE_ACCOUNT_FUNCTION" }

  override suspend fun deleteAccount(): AccountDeletionResult {
    return try {
      val user = auth.currentUser ?: return AccountDeletionResult.NotSignedIn
      // Each step can stay pending for a long time (offline, or a connection lost mid-answer).
      withTimeout(ACCOUNT_DELETION_TIMEOUT_MILLIS) { pendingWritesFailure() ?: post(idToken(user)) }
    } catch (_: TimeoutCancellationException) {
      // Our timeout, unless the caller was cancelled at the same moment.
      currentCoroutineContext().ensureActive()
      AccountDeletionResult.NetworkError
    } catch (_: CancellationException) {
      // A cancelled SDK task is a failure; cancellation of our coroutine must still propagate.
      currentCoroutineContext().ensureActive()
      AccountDeletionResult.UnexpectedError
    } catch (error: FirebaseAuthInvalidUserException) {
      // Only a missing user means "already deleted"; a disabled user or a revoked session is not.
      if (error.errorCode == USER_NOT_FOUND) AccountDeletionResult.Deleted
      else AccountDeletionResult.NotSignedIn
    } catch (_: FirebaseNetworkException) {
      AccountDeletionResult.NetworkError
    } catch (_: MalformedURLException) {
      AccountDeletionResult.UnexpectedError
    } catch (_: IOException) {
      // Includes SocketTimeoutException and UnknownHostException.
      AccountDeletionResult.NetworkError
    } catch (_: Exception) {
      AccountDeletionResult.UnexpectedError
    }
  }

  /**
   * Waits until Firestore has sent this user's queued writes. Returns null once they are sent, or
   * the result to return without deleting anything.
   */
  private suspend fun pendingWritesFailure(): AccountDeletionResult? =
      try {
        firestore().waitForPendingWrites().await()
        null
      } catch (_: CancellationException) {
        // Our timeout or the caller's cancellation propagates; a cancelled SDK task is a failure.
        currentCoroutineContext().ensureActive()
        AccountDeletionResult.NetworkError
      } catch (_: FirebaseFirestoreException) {
        AccountDeletionResult.NetworkError
      } catch (_: Exception) {
        // For example the session's database, blocked until a failed cache clear succeeds: a retry
        // can't help, so this is not a network error.
        AccountDeletionResult.UnexpectedError
      }

  /** The cached ID token, refreshed only if it has expired. */
  private suspend fun idToken(user: FirebaseUser): String =
      checkNotNull(user.getIdToken(false).await().token) { "No ID token" }

  private suspend fun post(token: String): AccountDeletionResult =
      withContext(ioDispatcher) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
          connection.requestMethod = "POST"
          connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
          connection.readTimeout = readTimeoutMillis
          connection.doOutput = true
          connection.setRequestProperty("Authorization", "Bearer $token")
          connection.setRequestProperty("Content-Type", "application/json")
          connection.outputStream.use { it.write(REQUEST_BODY.toByteArray()) }
          val status = connection.responseCode
          // An error status has its body on the error stream, which is null when there is none.
          val stream = if (status in 200..299) connection.inputStream else connection.errorStream
          val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
          parseDeleteAccountResponse(status, body)
        } finally {
          connection.disconnect()
        }
      }

  private companion object {
    /** The callable protocol wraps the argument in `data`; `deleteAccount` takes none. */
    const val REQUEST_BODY = """{"data":{}}"""

    /** Firebase Auth's error code for a user that no longer exists. */
    const val USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"
  }
}

/**
 * Where the app's functions are served: `https://<region>-<project>.cloudfunctions.net`, the
 * default URL of a function deployed in [ACCOUNT_DELETION_REGION].
 *
 * @throws IllegalStateException if the Firebase configuration has no project ID.
 */
internal fun deleteAccountBaseUrl(projectId: String?): String {
  val project = checkNotNull(projectId?.takeIf { it.isNotBlank() }) { "No Firebase project ID" }
  return "https://$ACCOUNT_DELETION_REGION-$project.cloudfunctions.net"
}
