// Contributors: Claude (Opus 5.5, Anthropic) drafted the callable response mapping for #206;
// Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.model.account

import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the `deleteAccount` callable's HTTP answer, following the callable protocol
 * (https://firebase.google.com/docs/functions/callable-reference):
 * - HTTP 200 with `{"result":{"deleted":true}}` → [AccountDeletionResult.Deleted]; any other
 *   success body → [AccountDeletionResult.UnexpectedError];
 * - an error body `{"error":{"status":…,"details":…}}`, by `status`: `UNAUTHENTICATED` →
 *   [AccountDeletionResult.NotSignedIn]; `FAILED_PRECONDITION` with details `recent-login-required`
 *   → [AccountDeletionResult.RecentLoginRequired], with `association` →
 *   [AccountDeletionResult.NotAllowed]; `UNAVAILABLE` and `DEADLINE_EXCEEDED` →
 *   [AccountDeletionResult.NetworkError] (retrying is safe);
 * - anything else (`INTERNAL`, `NOT_FOUND` when the function is not deployed, other details, a
 *   missing or malformed body) → [AccountDeletionResult.UnexpectedError].
 *
 * The `details` strings are the server's (`DETAILS` in
 * `firebase/functions/deleteAccountHandler.js`). This uses Android's `org.json`, so JVM tests of it
 * run under Robolectric.
 */
internal fun parseDeleteAccountResponse(status: Int, body: String): AccountDeletionResult =
    try {
      if (status == HTTP_OK) successResult(JSONObject(body)) else errorResult(JSONObject(body))
    } catch (_: JSONException) {
      AccountDeletionResult.UnexpectedError
    }

private fun successResult(answer: JSONObject): AccountDeletionResult =
    if (answer.optJSONObject("result")?.opt("deleted") == true) AccountDeletionResult.Deleted
    else AccountDeletionResult.UnexpectedError

private fun errorResult(answer: JSONObject): AccountDeletionResult {
  val error = answer.optJSONObject("error") ?: return AccountDeletionResult.UnexpectedError
  return when (error.optString("status")) {
    "UNAUTHENTICATED" -> AccountDeletionResult.NotSignedIn
    "FAILED_PRECONDITION" ->
        when (error.opt("details")) {
          RECENT_LOGIN_REQUIRED -> AccountDeletionResult.RecentLoginRequired
          ASSOCIATION -> AccountDeletionResult.NotAllowed
          else -> AccountDeletionResult.UnexpectedError
        }
    "UNAVAILABLE",
    "DEADLINE_EXCEEDED" -> AccountDeletionResult.NetworkError
    else -> AccountDeletionResult.UnexpectedError
  }
}

private const val HTTP_OK = 200

/** `details` of the function's failed-precondition errors. */
private const val RECENT_LOGIN_REQUIRED = "recent-login-required"
private const val ASSOCIATION = "association"
