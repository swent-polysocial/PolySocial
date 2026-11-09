// Contributors: Claude (Opus 5.5, Anthropic) drafted the Cloud Functions entry point, #206; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (the trigger deleting a deleted event's registrations, #275).
"use strict";

const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const logger = require("firebase-functions/logger");
const { onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onCall } = require("firebase-functions/v2/https");
const { REGION, deleteAccountHandler } = require("./deleteAccountHandler");
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
