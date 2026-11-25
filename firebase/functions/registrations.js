// Contributors: Claude (Opus 5.5, Anthropic) drafted the deletion of registrations with their event
// and account, #275; kept for an event written again, after review; deleteAllMatching shared with
// the friendships, #357).
// Contributors: Claude (Opus 5.5, Anthropic) lowered the event's attendeeCount with each
// registration an account deletion removes, from #372's prototype, #405.
// Contributors: Claude (Opus 5.5, Anthropic) shared the guarded batch deletion and the trigger's
// failure logging with the deleted groups' triggers, #382; the guard checks the event's creator,
// after review; a deletion without its document still cleans up, after review.
// Contributors: Claude (Opus 5.5, Anthropic) (check-ins and the entrance code deleted with the event
// and the account, #472); Konstantinos Perdikeas (directed, reviewed and owns it).
"use strict";

/**
 * Region (Belgium) of the Firestore triggers: deleteEventRegistrations and the deleted groups'
 * triggers (groups.js, #382). Firestore is in the eur3 multi-region, whose Firestore triggers
 * Eventarc delivers only to europe-west1 or europe-west4, so they can't run in deleteAccount's
 * europe-west6.
 */
const TRIGGER_REGION = "europe-west1";

/** Firestore's limit of writes in one batch. */
const MAX_BATCH_WRITES = 500;

/**
 * Deletes every document `query` matches, MAX_BATCH_WRITES at a time, and reads again until none
 * is left, so documents written in between are deleted too. Deleting a document that is already
 * gone is not an error, so a retry after a failure finishes the job.
 */
async function deleteAllMatching(db, query) {
  for (;;) {
    const snapshot = await query.limit(MAX_BATCH_WRITES).get();
    if (snapshot.empty) return;
    const batch = db.batch();
    for (const document of snapshot.docs) batch.delete(document.ref);
    await batch.commit();
  }
}

/**
 * Deletes the documents of the subcollection `collectionId` under the deleted document `parent`
 * (a deleted event's `attendees`, `groups` or `groupMembers`), MAX_BATCH_WRITES at a time, and
 * stops as soon as `parent` exists again **with the deleted one's `createdBy`**: it is then the
 * same creator's document written back, and what is under it belongs to it. The demo seed script
 * (`scripts/seedDemo.js`) deletes its events and writes them back under the same IDs, with the
 * same organizers, and a deletion trigger can run after that. Anyone else's document under that ID
 * doesn't stop it: a client chooses an event's ID, and the rules make its `createdBy` the creator's
 * UID, so a student who re-created a cancelled event's ID would otherwise keep its registrations
 * and groups. Each batch is a transaction that reads `parent` before deleting, so a document
 * written in between by the same creator can't lose what was written with it. Deleting a document
 * that is already gone is not an error, so a retry after a failure finishes the job.
 *
 * @param {string|undefined} createdBy the deleted document's `createdBy`; undefined when the
 *   deletion event came without the deleted document, which Firestore always sends. Then only a
 *   document written back without `createdBy` stops it, so it deletes rather than keeps.
 */
async function deleteOrphans(db, parent, collectionId, createdBy) {
  const children = parent.collection(collectionId).limit(MAX_BATCH_WRITES);
  for (;;) {
    const deleted = await db.runTransaction(async (transaction) => {
      const stored = await transaction.get(parent);
      if (stored.exists && stored.get("createdBy") === createdBy) return 0;
      const snapshot = await transaction.get(children);
      for (const document of snapshot.docs) transaction.delete(document.ref);
      return snapshot.size;
    });
    if (deleted === 0) return;
  }
}

/**
 * Runs a deletion trigger's `work`. A failure logs only `name` and the error's code (never an ID,
 * the error's message or its stack, which may hold personal data) and is thrown again, so the
 * trigger, deployed with retries, runs again until the work is done.
 */
async function runTriggerWork(name, logger, work) {
  try {
    await work();
  } catch (error) {
    const code = error && error.code;
    logger.error(`${name} failed`, {
      code: typeof code === "number" || typeof code === "string" ? code : null,
    });
    throw error;
  }
}

/**
 * Deletes every registration of the user `uid`, across events (the collection-group query
 * `attendees where uid == <uid>`, which the app's own-registrations query already indexes), each
 * with [deleteCountedRegistration], so every event's `attendeeCount` goes down with it (#372,
 * decision 21). It reads again until none is left, so a registration written in between is
 * deleted too. A student has one registration per event at most, so one transaction each is cheap.
 */
async function deleteUserRegistrations(db, uid) {
  const registrations = db
      .collectionGroup("attendees")
      .where("uid", "==", uid)
      .limit(MAX_BATCH_WRITES);
  for (;;) {
    const snapshot = await registrations.get();
    if (snapshot.empty) return;
    for (const registration of snapshot.docs) await deleteCountedRegistration(db, registration.ref);
  }
}

/**
 * Deletes every check-in of the user `uid`, across events (#472): the collection-group query
 * `checkIns where uid == <uid>`, with the index #467 deployed, in batches until none is left. A
 * check-in has no counter to lower, so a plain batch is enough. `deleteAccount` runs it after the
 * profile transaction, whose deletion marker makes the rules refuse any new check-in, so none can
 * be written behind it; deleting one that is already gone is not an error, so a retry finishes.
 */
function deleteUserCheckIns(db, uid) {
  return deleteAllMatching(db, db.collectionGroup("checkIns").where("uid", "==", uid));
}

/**
 * Deletes the registration [ref] (`events/{eventId}/attendees/{uid}`) and lowers its event's
 * `attendeeCount` by one, in one transaction, as the app's cancellation does through the Security
 * Rules (#372). The counter goes down only if the registration still exists when the transaction
 * reads it, and the event exists with a whole count above 0. So a retry after a failure, the
 * student's own cancellation or the deleted event's `deleteEventRegistrations` trigger getting
 * there first never lowers a counter twice; an event without a counter (written before #373) or
 * at 0 keeps it as it is, and a registration whose event is gone is simply deleted.
 */
async function deleteCountedRegistration(db, ref) {
  const event = ref.parent.parent;
  await db.runTransaction(async (transaction) => {
    const [registration, stored] = await transaction.getAll(ref, event);
    if (!registration.exists) return;
    transaction.delete(ref);
    const count = stored.exists ? stored.get("attendeeCount") : undefined;
    if (Number.isInteger(count) && count > 0) {
      transaction.update(event, { attendeeCount: count - 1 });
    }
  });
}

/**
 * What a deleted event takes with it, in this order: its registrations (#121), its check-ins and its
 * entrance code (#472, `checkInCode/current`).
 */
const EVENT_ATTENDANCE_COLLECTIONS = ["attendees", "checkIns", "checkInCode"];

/**
 * Runs when `events/{eventId}` is deleted: deletes the event's registrations
 * `events/{eventId}/attendees/*` (#121), check-ins `checkIns/*` and entrance code `checkInCode/*`
 * (#472), each with [deleteOrphans]. Once the event is gone, the rules refuse reading its
 * registrations and its code (they check the event), but not its check-ins: a student can still
 * read their own, and their group theirs, until this deletes them. The rules refuse a new
 * registration, check-in or code for a missing event, so none can appear afterwards, unless the
 * event is written again under the same ID; they are then kept if its creator wrote it again. A
 * failure is logged and thrown again by [runTriggerWork], so the trigger runs again, deleting what
 * is left, until all three are gone.
 *
 * @param {object} args
 * @param {string} args.eventId the deleted event's ID.
 * @param {object|undefined} args.deleted the deleted event's data, for its `createdBy`.
 * @param {object} args.db Firestore (Admin SDK), which bypasses the Security Rules.
 * @param {{error: function(string, object): void}} args.logger where a failure is logged.
 */
function deleteRegistrationsOfDeletedEvent({ eventId, deleted, db, logger }) {
  const event = db.collection("events").doc(eventId);
  return runTriggerWork("deleteEventRegistrations", logger, async () => {
    for (const collectionId of EVENT_ATTENDANCE_COLLECTIONS) {
      await deleteOrphans(db, event, collectionId, deleted?.createdBy);
    }
  });
}

module.exports = {
  MAX_BATCH_WRITES,
  deleteAllMatching,
  TRIGGER_REGION,
  deleteCountedRegistration,
  deleteOrphans,
  deleteRegistrationsOfDeletedEvent,
  deleteUserCheckIns,
  deleteUserRegistrations,
  runTriggerWork,
};
