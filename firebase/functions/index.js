// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #447 under Lucas Pinto Ricardo’s supervision and approval).
// Contributors: Claude (Opus 5.5, Anthropic) drafted the Cloud Functions entry point, #206; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (the trigger deleting a deleted event's registrations, #275).
// Contributors: Claude (Fable 5.2, Anthropic) (the scheduled cleanup of unverified accounts, #226).
// Contributors: Claude (Opus 5.5, Anthropic) (the deletion triggers' shared options and the deleted
// groups' triggers, #382; a deletion without its document, after review).
"use strict";

const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const logger = require("firebase-functions/logger");
const { onDocumentDeleted, onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onCall } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { REGION, deleteAccountHandler } = require("./deleteAccountHandler");
const { deleteUnverifiedAccountsHandler } = require("./deleteUnverifiedAccountsHandler");
const { deleteGroupsOfDeletedEvent, deleteMessagesOfDeletedGroup } = require("./groups");
const { TRIGGER_REGION, deleteRegistrationsOfDeletedEvent } = require("./registrations");

const { syncFriendLists } = require("./syncFriendLists");

initializeApp();

/** Callable: deletes the signed-in user's account. See deleteAccountHandler.js. */
exports.deleteAccount = onCall({ region: REGION }, (request) =>
  deleteAccountHandler({
    auth: request.auth,
    db: getFirestore(),
    adminAuth: getAuth(),
    now: Date.now,
    logger,
  }),
);

/**
 * A Firestore trigger on the deletion of a `document` matching the pattern, in TRIGGER_REGION and
 * deployed with retries, so a run that fails is run again until `cleanUp` succeeds. `cleanUp` gets
 * the pattern's parameters (such as `eventId`), the deleted document's data as `deleted`,
 * Firestore and the logger. A Firestore deletion event carries the deleted document (its
 * `oldValue`), so `deleted` is undefined only if an event ever came without it: the event cleanups
 * then don't know the creator and delete, as for a document someone else wrote back
 * ([deleteOrphans]).
 */
function onDeleted(document, cleanUp) {
  return onDocumentDeleted({ document, region: TRIGGER_REGION, retry: true }, (event) =>
    cleanUp({ ...event.params, deleted: event.data?.data(), db: getFirestore(), logger }),
  );
}

/** Trigger: deletes a deleted event's registrations. See registrations.js. */
exports.deleteEventRegistrations = onDeleted("events/{eventId}", deleteRegistrationsOfDeletedEvent);

/** Trigger: deletes a deleted event's groups and their markers (#382). See groups.js. */
exports.deleteEventGroups = onDeleted("events/{eventId}", deleteGroupsOfDeletedEvent);

/**
 * Trigger: deletes a deleted group's chat messages, whether its event was cancelled or its last
 * member left (#382). See groups.js.
 */
exports.deleteGroupMessages = onDeleted(
  "events/{eventId}/groups/{groupId}",
  deleteMessagesOfDeletedGroup,
);

/**
 * Scheduled: every night, deletes the email and password sign-ups whose email is still unverified
 * after the retention period. Without `timeZone`, the schedule would be read as UTC. See
 * deleteUnverifiedAccountsHandler.js.
 */
exports.deleteUnverifiedAccounts = onSchedule(
  { schedule: "every day 03:00", timeZone: "Europe/Zurich", region: REGION },
  () =>
    deleteUnverifiedAccountsHandler({
      adminAuth: getAuth(),
      db: getFirestore(),
      now: Date.now,
      logger,
    }),
);

/** Mirrors current friendships into both students' profile friend lists (#447). */
exports.syncFriendLists = onDocumentWritten(
  { document: "friendships/{pairId}", region: TRIGGER_REGION, retry: true },
  ({ params }) => syncFriendLists(getFirestore(), params.pairId),
);
