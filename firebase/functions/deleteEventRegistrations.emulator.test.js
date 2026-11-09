// Contributors: Claude (Opus 5.5, Anthropic) drafted the emulator test of the trigger deleting a
// deleted event's registrations, #275.
"use strict";

// Deletes events in the Firestore emulator and waits for the deleteEventRegistrations trigger,
// running in the Functions emulator. Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node --test --test-concurrency=1 firebase/functions/*.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

// A "demo-" project never reaches a real Firebase project.
const PROJECT_ID = "demo-polysocial";
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;
const FUNCTIONS_ORIGIN = "http://127.0.0.1:5001";
// How long the trigger may take to run in the emulator.
const TIMEOUT_MS = 15_000;

if (!FIRESTORE_HOST) {
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
  app = initializeApp({ projectId: PROJECT_ID }, "deleteEventRegistrations");
  db = getFirestore(app);
});

after(async () => {
  if (app) await deleteApp(app);
});

beforeEach(async () => {
  const response = await fetch(
    `http://${FIRESTORE_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
    { method: "DELETE" },
  );
  if (!response.ok) throw new Error(`Could not clear the emulator (${response.status}).`);
});

/** Stores a fictional event and a registration for it from each of `uids`. */
async function seedEvent(eventId, uids) {
  await db.doc(`events/${eventId}`).set({
    title: "Fictional study session",
    createdBy: "organizer",
    organizerIds: ["organizer"],
    allowedUids: ["organizer"],
    isPrivate: false,
  });
  for (const uid of uids) {
    await db.doc(`events/${eventId}/attendees/${uid}`).set({ uid, registeredAt: new Date() });
  }
}

/** The paths of every registration left, sorted. */
async function registrations() {
  const snapshot = await db.collectionGroup("attendees").get();
  return snapshot.docs.map((document) => document.ref.path).sort();
}

/** Waits until the registrations left are `expected`, or fails after TIMEOUT_MS. */
async function waitForRegistrations(expected) {
  const deadline = Date.now() + TIMEOUT_MS;
  let left = await registrations();
  while (JSON.stringify(left) !== JSON.stringify(expected)) {
    if (Date.now() > deadline) {
      assert.fail(`The trigger didn't run in time: ${JSON.stringify(left)} are left.`);
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
    left = await registrations();
  }
}

test("deleting an event deletes its registrations, other events' are kept", async () => {
  await seedEvent("deleted", ["u1", "u2"]);
  await seedEvent("kept", ["u1"]);

  await db.doc("events/deleted").delete();

  await waitForRegistrations(["events/kept/attendees/u1"]);
  assert.equal((await db.doc("events/kept").get()).exists, true);
});

test("an event with more registrations than one batch holds loses them all", async () => {
  const uids = Array.from({ length: 501 }, (_, i) => `u${i}`);
  await seedEvent("crowded", uids);
  await db.doc("events/crowded").delete();
  await waitForRegistrations([]);
});
