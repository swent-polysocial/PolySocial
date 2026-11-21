// Contributors: Claude (Opus 5.5, Anthropic) drafted the setup of the emulator test of
// deleteEventRegistrations, #275; moved here to share it with the deleted groups' emulator test,
// and its Firestore clear made to fire no trigger, #382.
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

/** The value of the environment variable `name`, which `firebase emulators:exec` sets. */
function emulatorEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is not set: run this test inside ` +
        '`firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "..."`.',
    );
  }
  return value;
}

/**
 * Turns the Functions emulator's background triggers (all but the callables) on or off through
 * the emulator hub. Turning them off first waits for those still running; while they are off,
 * writes fire none. Turning them on reloads the functions, which takes a few seconds.
 */
async function setBackgroundTriggers(enabled) {
  const hub = emulatorEnv("FIREBASE_EMULATOR_HUB");
  const action = enabled ? "enableBackgroundTriggers" : "disableBackgroundTriggers";
  const response = await fetch(`http://${hub}/functions/${action}`, { method: "PUT" });
  if (!response.ok) {
    throw new Error(
      `The emulator hub refused ${action} (${response.status}): is "functions" in --only?`,
    );
  }
}

/**
 * Deletes every document in the Firestore emulator with the background triggers turned off: that
 * waits for the triggers the previous test fired, and the cleared documents then fire none.
 * Otherwise a deletion trigger could run late and delete what the next test writes under the same
 * IDs, such as a group seeded under the ID of an event an earlier test stored.
 */
async function clearFirestore() {
  const firestoreHost = emulatorEnv("FIRESTORE_EMULATOR_HOST");
  await setBackgroundTriggers(false);
  try {
    const response = await fetch(
      `http://${firestoreHost}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
      { method: "DELETE" },
    );
    if (!response.ok) throw new Error(`Could not clear the emulator (${response.status}).`);
  } finally {
    await setBackgroundTriggers(true);
  }
}

/**
 * Registers the test file's hooks: before its tests, checks that the Functions emulator answers
 * and makes an Admin app named `appName` on the Firestore emulator; before each test, clears
 * Firestore with [clearFirestore]; after them, deletes the app. Fails at once, with the command to
 * run, outside `firebase emulators:exec`. Returns a function giving the app's Firestore once the
 * tests run.
 */
function useTriggerEmulators(appName) {
  emulatorEnv("FIRESTORE_EMULATOR_HOST");
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
  beforeEach(clearFirestore);
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

module.exports = { pathsIn, setBackgroundTriggers, useTriggerEmulators, waitFor, waitUntil };
