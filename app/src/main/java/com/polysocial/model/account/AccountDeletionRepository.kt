// Contributors: Claude (Fable 5.1, Anthropic) drafted the account deletion contract; Mohamed
// Khellaf (reviewed and revised).
// Contributors: Claude (Opus 5.5, Anthropic) documented the local sign-out and the uid to capture,
// #206; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.account

/** Outcome of [AccountDeletionRepository.deleteAccount]. */
sealed interface AccountDeletionResult {
  /** The profile and the sign-in account are both gone; the caller clears local data. */
  data object Deleted : AccountDeletionResult

  /** Nobody is signed in, so there is nothing this device may delete. */
  data object NotSignedIn : AccountDeletionResult

  /** The last sign-in is too old for the server; re-authenticate, then try again. */
  data object RecentLoginRequired : AccountDeletionResult

  /** The account may not delete itself in the app (an association; see the privacy contact). */
  data object NotAllowed : AccountDeletionResult

  /** The server could not be reached or was unavailable; retrying is safe. */
  data object NetworkError : AccountDeletionResult

  data object UnexpectedError : AccountDeletionResult
}

/**
 * Deletes the signed-in account and its data through the `deleteAccount` Cloud Function (#206):
 * first `users/{uid}`, then the Firebase Authentication user. Events the user created are kept.
 *
 * Callers re-authenticate first (`AuthRepository.reauthenticateWithPassword` or
 * `reauthenticateWithGoogle`) and check the connection, since a deletion is never queued offline.
 * Every server step is idempotent, so retrying after any failure is safe. Returns
 * [AccountDeletionResult.Deleted] only once both steps are done, or when the account turns out to
 * be already gone; clearing the local cache and signing out stay with the caller. Caller
 * cancellation propagates.
 *
 * When Firebase finds the account gone, disabled or its session revoked, it signs the user out
 * locally, so `currentUser` is null afterwards: capture the uid (for the local cleanup) before
 * starting the flow.
 */
interface AccountDeletionRepository {
  suspend fun deleteAccount(): AccountDeletionResult
}
