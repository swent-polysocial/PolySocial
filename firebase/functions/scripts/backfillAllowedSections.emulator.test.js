// Contributors: Claude (Sonnet 5.5, Anthropic) (the allowedSections backfill against the Firestore
// emulator, from the attendee recount's, #369).
"use strict";

// Runs the backfill against the Firestore emulator, with the Admin SDK as the script does. Run
// from the repository root (see the README, "Backfilling allowedSections"):
// firebase emulators:exec --project demo-polysocial --only firestore "node firebase/functions/scripts/backfillAllowedSections.emulator.test.js"
// A "demo-" project never reaches a real Firebase project.
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { backfillAllowedSections } = require("./backfillAllowedSections");

const PROJECT_ID = "demo-polysocial";
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;
const ROOT = `projects/${PROJECT_ID}/databases/(default)/documents`;

if (!FIRESTORE_HOST) {
  throw new Error(
    "FIRESTORE_EMULATOR_HOST is not set: run this test inside " +
      '`firebase emulators:exec --project demo-polysocial --only firestore "..."`.',
  );
}

let app;
let db;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, "backfill-allowed-sections-test");
  db = getFirestore(app);
});

after(() => deleteApp(app));

beforeEach(async () => {
  const response = await fetch(`http://${FIRESTORE_HOST}/emulator/v1/${ROOT}`, {
    method: "DELETE",
  });
  if (!response.ok) throw new Error(`Couldn't clear the Firestore emulator: HTTP ${response.status}`);
});

async function sectionsOf(eventId) {
  const event = await db.doc(`events/${eventId}`).get();
  return event.exists ? (event.get("allowedSections") ?? "missing") : "no event";
}

test("every event ends with an allowedSections list, and the other fields stay", async () => {
  await db.doc("events/legacy").set({ title: "Legacy", isPrivate: false, attendeeCount: 3 });
  await db.doc("events/privateLegacy").set({ title: "Private", isPrivate: true });
  await db.doc("events/open").set({ title: "Open", allowedSections: [] });
  await db.doc("events/restricted").set({ title: "Restricted", allowedSections: ["IN", "SC"] });

  assert.deepEqual(await backfillAllowedSections(db), { events: 4, changed: 2 });

  assert.deepEqual(await sectionsOf("legacy"), []);
  assert.deepEqual(await sectionsOf("privateLegacy"), []);
  assert.deepEqual(await sectionsOf("open"), []);
  assert.deepEqual(await sectionsOf("restricted"), ["IN", "SC"]);
  const legacy = await db.doc("events/legacy").get();
  assert.equal(legacy.get("title"), "Legacy");
  assert.equal(legacy.get("attendeeCount"), 3);
  assert.deepEqual(await backfillAllowedSections(db), { events: 4, changed: 0 });
});

test("a project with no events is not an error", async () => {
  assert.deepEqual(await backfillAllowedSections(db), { events: 0, changed: 0 });
});
