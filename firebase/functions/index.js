// Contributors: Claude (Opus 5.5, Anthropic) drafted the Cloud Functions entry point, #206; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (the trigger deleting a deleted event's registrations, #275).
// Contributors: Claude (Fable 5.2, Anthropic) (the scheduled cleanup of unverified accounts, #226).
"use strict";

const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const logger = require("firebase-functions/logger");
const { onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onCall } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { REGION, deleteAccountHandler } = require("./deleteAccountHandler");
const { deleteUnverifiedAccountsHandler } = require("./deleteUnverifiedAccountsHandler");
const { TRIGGER_REGION, deleteRegistrationsOfDeletedEvent } = require("./registrations");

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

/** Trigger: deletes a deleted event's registrations, retried until done. See registrations.js. */
exports.deleteEventRegistrations = onDocumentDeleted(
  { document: "events/{eventId}", region: TRIGGER_REGION, retry: true },
  (event) =>
    deleteRegistrationsOfDeletedEvent({
      eventId: event.params.eventId,
      db: getFirestore(),
      logger,
    }),
);

/**
 * Scheduled: every night, deletes the accounts whose email is still unverified after the retention
 * period. Without `timeZone`, the schedule would be read as UTC. See
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
