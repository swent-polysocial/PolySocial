// Contributors: Claude (Opus 5.5, Anthropic) drafted the deletion of registrations with their event
// and account, #275; kept for an event written again, after review.
"use strict";

/**
 * Region of the deleteEventRegistrations trigger (Belgium). Firestore is in the eur3 multi-region,
 * whose Firestore triggers Eventarc delivers only to europe-west1 or europe-west4, so the trigger
 * can't run in deleteAccount's europe-west6.
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
 * Deletes the registrations `events/{eventId}/attendees/*` of a deleted event (#121),
 * MAX_BATCH_WRITES at a time, and stops as soon as `events/{eventId}` exists again: its
 * registrations then belong to the new event. The demo seed script (`scripts/seedDemo.js`) deletes
 * its events and writes them back under the same IDs, with new registrations, and this trigger
 * can run after that. Each batch is a transaction that reads the event before deleting, so an
 * event written in between can't lose the registrations written with it.
 */
async function deleteEventRegistrations(db, eventId) {
  const event = db.collection("events").doc(eventId);
  const attendees = event.collection("attendees").limit(MAX_BATCH_WRITES);
  for (;;) {
    const deleted = await db.runTransaction(async (transaction) => {
      if ((await transaction.get(event)).exists) return 0;
      const snapshot = await transaction.get(attendees);
      for (const document of snapshot.docs) transaction.delete(document.ref);
      return snapshot.size;
    });
    if (deleted === 0) return;
  }
}

/**
 * Deletes every registration of the user `uid`, across events: the collection-group query
 * `attendees where uid == <uid>`, which the app's own-registrations query already indexes.
 */
function deleteUserRegistrations(db, uid) {
  return deleteAllMatching(db, db.collectionGroup("attendees").where("uid", "==", uid));
}

/**
 * Runs when `events/{eventId}` is deleted: deletes the event's registrations, which nobody can list
 * once the event is gone (the rules check the event's organizers). The rules refuse a new
 * registration for a missing event, so none can appear afterwards, unless the event is written
 * again under the same ID; its registrations are then kept. A failure logs only the error's
 * code (never the event or a uid) and is thrown again, so the trigger, deployed with retries, runs
 * again until the registrations are gone.
 *
 * @param {object} args
 * @param {string} args.eventId the deleted event's ID.
 * @param {object} args.db Firestore (Admin SDK), which bypasses the Security Rules.
 * @param {{error: function(string, object): void}} args.logger where a failure is logged.
 */
async function deleteRegistrationsOfDeletedEvent({ eventId, db, logger }) {
  try {
    await deleteEventRegistrations(db, eventId);
  } catch (error) {
    const code = error && error.code;
    logger.error("deleteEventRegistrations failed", {
      code: typeof code === "number" || typeof code === "string" ? code : null,
    });
    throw error;
  }
}

module.exports = {
  MAX_BATCH_WRITES,
  TRIGGER_REGION,
  deleteRegistrationsOfDeletedEvent,
  deleteUserRegistrations,
};
