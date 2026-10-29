// Contributors: Claude (Fable 5.1, Anthropic) drafted the shared deletion constants; Mohamed
// Khellaf (reviewed and revised).
// Contributors: Claude (Opus 5.5, Anthropic) corrected where the server defines REGION; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.model.account

/**
 * Region of the `deleteAccount` Cloud Function. It must match `REGION` in
 * `firebase/functions/deleteAccountHandler.js`, or the callable is not found.
 */
const val ACCOUNT_DELETION_REGION = "europe-west6"

/** Name of the callable Cloud Function that deletes the signed-in account (#206). */
const val DELETE_ACCOUNT_FUNCTION = "deleteAccount"
