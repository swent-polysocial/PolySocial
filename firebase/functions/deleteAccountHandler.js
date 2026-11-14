// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount handler, #206; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (the public profile deleted with the account, #229; the deletion marker,
// after review, #229; the user's registrations, #275).
"use strict";

// Contributors: OpenAI Codex (retryable membership cleanup and message author removal before Auth deletion, #241).
// Contributors: Claude (Fable 5.2, Anthropic) (errorCode shared with the unverified accounts cleanup, and REGION's KDoc for it, #226).

const { HttpsError } = require("firebase-functions/v2/https");
const { deleteUserRegistrations, MAX_BATCH_WRITES } = require("./registrations");

/**
 * Region (Zurich) of the deleteAccount callable and of the scheduled deleteUnverifiedAccounts
 * (#226), whose Cloud Scheduler job the deploy creates in the same region. The Android client
 * calls deleteAccount with ACCOUNT_DELETION_REGION in
 * app/src/main/java/com/polysocial/model/account/AccountDeletion.kt: both must match, or the
 * client reaches no function.
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
 * Deletes the signed-in user's account: first their private profile `users/{uid}` and their public
 * profile `publicProfiles/{uid}` (#229), together, then their registrations for events (#275), then
 * their messages' author links and group memberships (#241), then their Firebase Auth user.
 * Returns `{ deleted: true }` only once all are gone. The same
 * transaction writes the deletion marker `deletedAccounts/{uid}`: the deleted user's ID token stays
 * valid until it expires (up to an hour), and the Security Rules refuse to recreate either profile
 * under a marked UID, so another of the user's devices can't bring the public profile back after
 * the deletion (#229 review). They refuse a new registration under a marked UID too, which is why
 * the registrations are deleted after the marker is written: none can be added once they are
 * deleted.
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
 * Group membership cleanup mirrors leaveGroup; the deletion marker prevents rejoining while it
 * runs. Message text and time are retained, with an empty senderId instead of the account link.
 * Cleanup of further personal data is added here by the issues that store it.
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
 * @param {{error: function(string, object): void, warn: function(string, object): void}} args.logger where failed steps are logged
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
  await callBackend("delete-registrations", logger, () => deleteUserRegistrations(db, uid));
  await callBackend("anonymise-messages", logger, () => anonymiseMessages(db, uid));
  await callBackend("delete-group-memberships", logger, () => deleteGroupMemberships(db, uid, logger));
  await callBackend("delete-auth-user", logger, () => deleteAuthUser(adminAuth, uid));
  return { deleted: true };
}

/**
 * Removes membership and its per-event marker together, using the same shape as leaveGroup (#232).
 * The deletion marker has already been committed: rules prevent this UID from starting/joining a
 * group with its still-valid token while cleanup runs. Transactions reread each group so another
 * member's concurrent join/leave is preserved. Empty groups are removed, as with ordinary leave.
 * A failure leaves Auth intact and Retry repeats the query, finding only unfinished memberships.
 * A malformed membership list is reported without data; remove the UID from arrays while
 * preserving all other entries. A scalar/missing list no longer contains a membership to remove.
 */
async function deleteGroupMemberships(db, uid, logger) {
  const groups = await db.collectionGroup("groups").where("memberUids", "array-contains", uid).get();
  for (const group of groups.docs) {
    if (!/^events\/[^/]+\/groups\/[^/]+$/.test(group.ref.path)) {
      throw new TypeError("Unexpected group path");
    }
    const marker = db.doc(`${group.ref.parent.parent.path}/groupMembers/${uid}`);
    await db.runTransaction(async (transaction) => {
      const current = await transaction.get(group.ref);
      if (current.exists) {
        const members = current.get("memberUids");
        if (!Array.isArray(members) || members.some((member) => typeof member !== "string")) {
          logger.warn("deleteAccount malformed group members", { code: "invalid-members" });
        }
        const remaining = Array.isArray(members) ? members.filter((member) => member !== uid) : null;
        if (remaining?.length === 0) transaction.delete(group.ref);
        else if (remaining && remaining.length !== members.length) {
          transaction.update(group.ref, { memberUids: remaining });
        }
      }
      transaction.delete(marker);
    });
  }
}

/**
 * Remove the account link from every message sent by this UID, including chats the user already
 * left and messages below deleted groups/events. The merged schema stores only senderId, text and
 * createdAt; an empty senderId is the explicit deleted-author marker and cannot be a Firebase UID.
 * Client rules cannot create it, alter messages, or send after the deletion marker was written.
 * Repeating the bounded query finds only unfinished work after a failed batch or lost answer.
 */
async function anonymiseMessages(db, uid) {
  const query = db.collectionGroup("messages").where("senderId", "==", uid).limit(MAX_BATCH_WRITES);
  for (;;) {
    const snapshot = await query.get();
    if (snapshot.empty) return;
    const batch = db.batch();
    for (const message of snapshot.docs) batch.update(message.ref, { senderId: "" });
    await batch.commit();
  }
}

/** Whether the token's `auth_time` (seconds) is at most MAX_AUTH_AGE_MS before `nowMillis`. */
function isRecentSignIn(token, nowMillis) {
  const authTime = token && token.auth_time;
  if (typeof authTime !== "number" || !Number.isFinite(authTime)) return false;
  return nowMillis - authTime * 1000 <= MAX_AUTH_AGE_MS;
}

/**
 * Deletes `users/{uid}` and the student's public profile `publicProfiles/{uid}` (#229) unless the
 * account is an association's, in one transaction, so no public copy outlives the account. The
 * transaction also writes the empty marker `deletedAccounts/{uid}`, which the Security Rules check,
 * so neither profile can be written again for this UID. It holds no data besides its ID.
 */
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
    // Writing the marker again and deleting a missing document are no-ops, so a retry after this
    // step still succeeds, and so does a student whose profile was saved before public profiles
    // existed.
    transaction.set(db.collection("deletedAccounts").doc(uid), {});
    transaction.delete(profile);
    transaction.delete(db.collection("publicProfiles").doc(uid));
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
  errorCode,
};
