// Contributors: Claude (Fable 5.2, Anthropic) drafted the scheduled cleanup of unverified accounts, #226.
"use strict";

const { errorCode } = require("./deleteAccountHandler");
const { MAX_BATCH_WRITES } = require("./registrations");

/**
 * How long an account may keep an unverified email before the daily cleanup deletes it, in days.
 * The one place to change the period. The README's Cloud Functions section and the app's privacy
 * notice (`privacy_notice_device_body` in strings.xml) state it too: change them together.
 */
const UNVERIFIED_ACCOUNT_RETENTION_DAYS = 7;

const RETENTION_MS = UNVERIFIED_ACCOUNT_RETENTION_DAYS * 24 * 60 * 60 * 1000;

/** Accounts listed per page: the most `listUsers` returns at once. */
const LIST_PAGE_SIZE = 1000;

/**
 * Deletes the abandoned sign-ups: the Firebase Auth accounts whose email is still unverified more
 * than UNVERIFIED_ACCOUNT_RETENTION_DAYS after their creation, so a mistyped address is free again
 * for its real owner (#149, #226). It lists every Auth user, LIST_PAGE_SIZE at a time, and never
 * touches a verified account, an unverified one that is exactly the retention period old or
 * younger, or one whose creation time can't be read.
 *
 * The abandoned accounts are then deleted MAX_BATCH_WRITES at a time (one Firestore batch, and
 * under the 1000 users of one `deleteUsers` call): first their `users/{uid}` documents, then the
 * accounts themselves. An unverified account has no such document, since every Security Rule
 * requires a verified email, so this only removes one written some other way. For the same reason
 * it has no public profile, registration, group or message to clean up, and needs no
 * `deletedAccounts/{uid}` marker: the rules refuse its ID token before and after. The accounts go
 * last, as in deleteAccount: when their documents can't be deleted they are kept, so the next run
 * finds them and tries both again, and no document outlives its account.
 *
 * A failure doesn't stop the run. A page that can't be listed ends the listing, since the next
 * page's token comes with the page, but the accounts found on the pages before it are still
 * deleted; a batch whose documents or accounts can't be deleted is counted and the next batch is
 * tried. Deleting is idempotent and the accounts left are still abandoned, so the next daily run
 * finishes the job. That includes a batch refused for quota: the server accepts one `deleteUsers`
 * call per second and the batches don't wait for each other, which only matters with more than
 * MAX_BATCH_WRITES abandoned accounts in one run.
 *
 * Logs counts and error codes only, never a uid, an email, an error's message or its stack: each
 * failed step with its name and codes, then one summary with the number of accounts deleted and of
 * those that should have been but weren't. After any failure it throws an Error with a fixed
 * message, so the run is reported as failed (the scheduler doesn't retry it; the next day's run
 * does).
 *
 * @param {object} args
 * @param {object} args.adminAuth Firebase Auth (Admin SDK): `listUsers` and `deleteUsers`.
 * @param {object} args.db Firestore (Admin SDK): `collection(path).doc(id)` and `batch()`.
 * @param {function(): number} args.now the current time, in milliseconds since the epoch.
 * @param {{info: function(string, object): void, error: function(string, object): void}}
 *   args.logger where the summary and the failed steps are logged (`firebase-functions/logger`
 *   in production).
 * @returns {Promise<number>} the number of accounts deleted, when nothing failed.
 */
async function deleteUnverifiedAccountsHandler({ adminAuth, db, now, logger }) {
  const createdBefore = now() - RETENTION_MS;
  const summary = { deleted: 0, failed: 0 };
  let anyFailure = false;
  const logFailure = (step, errors) => {
    anyFailure = true;
    const codes = [...new Set(errors.map(errorCode))];
    logger.error("deleteUnverifiedAccounts step failed", { step, codes });
  };

  const abandoned = [];
  let pageToken;
  do {
    let page;
    try {
      page = await adminAuth.listUsers(LIST_PAGE_SIZE, pageToken);
    } catch (error) {
      logFailure("list-users", [error]);
      break;
    }
    for (const user of page.users) {
      if (isAbandoned(user, createdBefore)) abandoned.push(user.uid);
    }
    pageToken = page.pageToken;
  } while (pageToken);

  for (let start = 0; start < abandoned.length; start += MAX_BATCH_WRITES) {
    const uids = abandoned.slice(start, start + MAX_BATCH_WRITES);
    let step = "delete-profiles";
    try {
      await deleteStoredProfiles(db, uids);
      step = "delete-auth-users";
      const result = await adminAuth.deleteUsers(uids);
      const errors = result.errors.map(({ error }) => error);
      summary.deleted += result.successCount;
      summary.failed += result.failureCount;
      if (result.failureCount > 0) logFailure(step, errors);
    } catch (error) {
      summary.failed += uids.length;
      logFailure(step, [error]);
    }
  }

  logger.info("deleteUnverifiedAccounts finished", summary);
  if (anyFailure) throw new Error("deleteUnverifiedAccounts failed: see its failed steps.");
  return summary.deleted;
}

/**
 * Whether the account's email is unverified and it was created before `createdBefore`
 * (milliseconds since the epoch). Only the boolean `false` counts as unverified, and a missing or
 * unreadable creation time as not old enough, so an account is never deleted on a guess.
 */
function isAbandoned(user, createdBefore) {
  if (user.emailVerified !== false) return false;
  const createdAt = Date.parse(user.metadata && user.metadata.creationTime);
  return Number.isFinite(createdAt) && createdAt < createdBefore;
}

/** Deletes the `users/{uid}` document, if any, of each of `uids` (at most MAX_BATCH_WRITES). */
async function deleteStoredProfiles(db, uids) {
  const batch = db.batch();
  for (const uid of uids) batch.delete(db.collection("users").doc(uid));
  await batch.commit();
}

module.exports = {
  UNVERIFIED_ACCOUNT_RETENTION_DAYS,
  deleteUnverifiedAccountsHandler,
};
