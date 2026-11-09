// Contributors: Claude (Opus 5.5, Anthropic) drafted the deletion of registrations with their event
// and account, #275.
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

/** Deletes the registrations `events/{eventId}/attendees/*` of an event (#121). */
function deleteEventRegistrations(db, eventId) {
  return deleteAllMatching(db, db.collection("events").doc(eventId).collection("attendees"));
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
 * registration for a missing event, so none can appear afterwards. A failure logs only the error's
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
