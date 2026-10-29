// Contributors: Claude (Opus 5.5, Anthropic) drafted the Cloud Functions entry point, #206; Mohamed Khellaf (reviewed and revised).
"use strict";

const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const logger = require("firebase-functions/logger");
const { onCall } = require("firebase-functions/v2/https");
const { REGION, deleteAccountHandler } = require("./deleteAccountHandler");

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
