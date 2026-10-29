// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount handler, #206; Mohamed Khellaf (reviewed and revised).
"use strict";

const { HttpsError } = require("firebase-functions/v2/https");

/**
 * Region of the deleteAccount callable (Zurich). The Android client calls it with
 * ACCOUNT_DELETION_REGION in app/src/main/java/com/polysocial/model/account/AccountDeletion.kt,
 * which arrives with the app's follow-up PR (#206, branch feature/206-delete-account-repository):
 * both must match, or the client reaches no function.
 */
const REGION = "europe-west6";

/** A deletion needs a sign-in at most this old (the ID token's auth_time), in milliseconds. */
const MAX_AUTH_AGE_MS = 5 * 60 * 1000;

/** The `details` of a failed-precondition error, so the client can tell the cases apart. */
const DETAILS = Object.freeze({
  RECENT_LOGIN_REQUIRED: "recent-login-required",
  ASSOCIATION: "association",
});

// gRPC status codes of a Firestore call worth retrying: DEADLINE_EXCEEDED, ABORTED (the
// transaction lost to a concurrent write too many times) and UNAVAILABLE.
const TRANSIENT_GRPC_CODES = new Set([4, 10, 14]);
// The same as strings, plus the Admin SDK's network errors and Node's socket errors.
const TRANSIENT_CODES = new Set([
  "deadline-exceeded",
  "aborted",
  "unavailable",
  "app/network-error",
  "app/network-timeout",
  "ECONNREFUSED",
  "ECONNRESET",
  "ETIMEDOUT",
  "ENOTFOUND",
  "EAI_AGAIN",
]);

/**
 * Deletes the signed-in user's account: first their private profile `users/{uid}`, then their
 * Firebase Auth user. Returns `{ deleted: true }` only once both are gone.
 *
 * Refuses, before deleting anything:
 * - a call without a signed-in user (`unauthenticated`);
 * - a sign-in older than MAX_AUTH_AGE_MS, or a token without `auth_time` (`failed-precondition`,
 *   details DETAILS.RECENT_LOGIN_REQUIRED), so a forgotten unlocked phone can't delete the account;
 * - an association account (`failed-precondition`, details DETAILS.ASSOCIATION): the PolySocial
 *   team deletes those by hand, through the privacy contact. A missing profile or a profile without
 *   `accountType` is a student's, so an account that never got a profile can delete itself.
 *
 * Each step is idempotent, so the client can simply call again after a failure: a profile already
 * deleted is not an error, and neither is an Auth user that no longer exists. The association check
 * and the profile deletion run in one transaction, so a profile created in between can't escape the
 * check. Events the user created are never touched (the deletion policy, #201, keeps them).
 * Cleanup of other personal data is added here by the issues that store it.
 *
 * Failures are HttpsErrors with a generic message (never the uid or the original error):
 * `unavailable` when the backend could not be reached and a retry may work, `internal` otherwise.
 * For diagnosis, a failed step logs only its name and the error's code, never the uid, the error
 * message or the stack (they may hold personal data). Refusals and successes log nothing.
 *
 * @param {object} args
 * @param {{uid: string, token: {auth_time?: number}} | undefined} args.auth the callable's
 *   `request.auth`, undefined when nobody is signed in.
 * @param {object} args.db Firestore (Admin SDK): `collection(path).doc(id)` and `runTransaction`.
 * @param {{deleteUser: function(string): Promise<void>}} args.adminAuth Firebase Auth (Admin SDK).
 * @param {function(): number} args.now the current time, in milliseconds since the epoch.
 * @param {{error: function(string, object): void}} args.logger where failed steps are logged
 *   (`firebase-functions/logger` in production).
 * @returns {Promise<{deleted: true}>}
 */
async function deleteAccountHandler({ auth, db, adminAuth, now, logger }) {
  if (!auth || typeof auth.uid !== "string" || auth.uid === "") {
    throw new HttpsError("unauthenticated", "Sign in to delete your account.");
  }
  if (!isRecentSignIn(auth.token, now())) {
    throw new HttpsError(
      "failed-precondition",
      "Sign in again to delete your account.",
      DETAILS.RECENT_LOGIN_REQUIRED,
    );
  }
  const uid = auth.uid;

  await callBackend("delete-profile", logger, () => deleteStudentProfile(db, uid));
  await callBackend("delete-auth-user", logger, () => deleteAuthUser(adminAuth, uid));
  return { deleted: true };
}

/** Whether the token's `auth_time` (seconds) is at most MAX_AUTH_AGE_MS before `nowMillis`. */
function isRecentSignIn(token, nowMillis) {
  const authTime = token && token.auth_time;
  if (typeof authTime !== "number" || !Number.isFinite(authTime)) return false;
  return nowMillis - authTime * 1000 <= MAX_AUTH_AGE_MS;
}

/** Deletes `users/{uid}` unless it is an association's, in one transaction. */
async function deleteStudentProfile(db, uid) {
  const profile = db.collection("users").doc(uid);
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(profile);
    if (snapshot.exists && snapshot.get("accountType") === "association") {
      throw new HttpsError(
        "failed-precondition",
        "Association accounts are deleted by the PolySocial team.",
        DETAILS.ASSOCIATION,
      );
    }
    // Deleting a missing document is a no-op, so a retry after this step still succeeds.
    transaction.delete(profile);
  });
}

/** Deletes the Auth user; one that no longer exists counts as deleted. */
async function deleteAuthUser(adminAuth, uid) {
  try {
    await adminAuth.deleteUser(uid);
  } catch (error) {
    if (error && error.code === "auth/user-not-found") return;
    throw error;
  }
}

/**
 * Runs one backend step and turns its failure into a typed, generic HttpsError, logging only the
 * step's name and the error's code.
 */
async function callBackend(stepName, logger, step) {
  try {
    await step();
  } catch (error) {
    if (error instanceof HttpsError) throw error;
    logger.error("deleteAccount step failed", { step: stepName, code: errorCode(error) });
    if (isTransient(error)) {
      throw new HttpsError("unavailable", "Account deletion is unavailable, try again.");
    }
    throw new HttpsError("internal", "Account deletion failed.");
  }
}

/** The error's code if it is a number or a string (gRPC, Admin SDK or Node codes), else null. */
function errorCode(error) {
  const code = error && error.code;
  return typeof code === "number" || typeof code === "string" ? code : null;
}

/** Whether an error says the backend could not be reached, so a retry may succeed. */
function isTransient(error) {
  const code = error && error.code;
  return TRANSIENT_GRPC_CODES.has(code) || TRANSIENT_CODES.has(code);
}

module.exports = {
  DETAILS,
  MAX_AUTH_AGE_MS,
  REGION,
  deleteAccountHandler,
};
