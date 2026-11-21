// Contributors: Claude (Opus 5.5, Anthropic) drafted the setup of the emulator test of
// deleteEventRegistrations, #275; moved here to share it with the deleted groups' emulator test,
// #382.
"use strict";

// Setup shared by the emulator tests that delete documents in the Firestore emulator and wait for
// a deletion trigger running in the Functions emulator. This folder isn't deployed (firebase.json's
// functions "ignore").
const { after, before, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

// A "demo-" project never reaches a real Firebase project.
const PROJECT_ID = "demo-polysocial";
const FUNCTIONS_ORIGIN = "http://127.0.0.1:5001";
// How long a trigger may take to run in the emulator.
const TIMEOUT_MS = 15_000;

/**
 * Registers the test file's hooks: before its tests, checks that the Functions emulator answers
 * and makes an Admin app named `appName` on the Firestore emulator; before each test, clears
 * Firestore; after them, deletes the app. Fails at once, with the command to run, outside
 * `firebase emulators:exec`. Returns a function giving the app's Firestore once the tests run.
 */
function useTriggerEmulators(appName) {
  const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
  if (!firestoreHost) {
    throw new Error(
      "FIRESTORE_EMULATOR_HOST is not set: run this test inside " +
        '`firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "..."`.',
    );
  }
  let app;
  let db;
  before(async () => {
    try {
      await fetch(FUNCTIONS_ORIGIN);
    } catch (error) {
      throw new Error(
        `The Functions emulator does not answer at ${FUNCTIONS_ORIGIN}: add "functions" to --only.`,
        { cause: error },
      );
    }
    app = initializeApp({ projectId: PROJECT_ID }, appName);
    db = getFirestore(app);
  });
  after(async () => {
    if (app) await deleteApp(app);
  });
  beforeEach(async () => {
    const response = await fetch(
      `http://${firestoreHost}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
      { method: "DELETE" },
    );
    if (!response.ok) throw new Error(`Could not clear the emulator (${response.status}).`);
  });
  return () => db;
}

/**
 * Reads `read()` every 200 ms until `isDone` accepts what it returned, and returns that, or fails
 * after TIMEOUT_MS with what was read last.
 */
async function waitUntil(read, isDone) {
  const deadline = Date.now() + TIMEOUT_MS;
  let value = await read();
  while (!isDone(value)) {
    if (Date.now() > deadline) {
      assert.fail(`The trigger didn't run in time: ${JSON.stringify(value)} is left.`);
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
    value = await read();
  }
  return value;
}

/** Waits until `read()` returns `expected` (compared as JSON), or fails after TIMEOUT_MS. */
function waitFor(read, expected) {
  return waitUntil(read, (value) => JSON.stringify(value) === JSON.stringify(expected));
}

/** The sorted paths of every document of the collection group `collectionId`. */
async function pathsIn(db, collectionId) {
  const snapshot = await db.collectionGroup(collectionId).get();
  return snapshot.docs.map((document) => document.ref.path).sort();
}

module.exports = { pathsIn, useTriggerEmulators, waitFor, waitUntil };
