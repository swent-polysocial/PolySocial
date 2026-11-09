// Contributors: Claude (the demo seed against the Firestore emulator, written test-first, #243).
"use strict";

// Seeds and removes the demo data in the Firestore emulator, with the Admin SDK as the script does.
// Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore "node firebase/functions/scripts/seedDemo.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { GeoPoint, Timestamp, getFirestore } = require("firebase-admin/firestore");
const { demoPaths } = require("./demoData");
const { removeDemo, seedDemo } = require("./seedDemo");

// A "demo-" project never reaches a real Firebase project.
const PROJECT_ID = "demo-polysocial";
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;
if (!FIRESTORE_HOST) {
  throw new Error(
    "FIRESTORE_EMULATOR_HOST is not set: run this test inside " +
      '`firebase emulators:exec --project demo-polysocial --only firestore "..."`.',
  );
}

const NOW = new Date("2026-11-09T09:00:00Z");
// Documents that aren't the demo's, such as a teammate's real test data.
const OTHERS = ["users/real-student", "events/real-event", "events/real-event/attendees/real-student"];

let app;
let db;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, "seed-demo-test");
  db = getFirestore(app);
});

after(() => deleteApp(app));

beforeEach(async () => {
  const response = await fetch(
    `http://${FIRESTORE_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
    { method: "DELETE" },
  );
  if (!response.ok) throw new Error(`Couldn't clear the Firestore emulator: HTTP ${response.status}`);
  await Promise.all(OTHERS.map((path) => db.doc(path).set({ kept: true })));
});

async function existing(paths) {
  const snapshots = await Promise.all(paths.map((path) => db.doc(path).get()));
  return snapshots.filter((snapshot) => snapshot.exists).map((snapshot) => snapshot.ref.path);
}

test("seeding writes every demo document, with the types the app reads", async () => {
  await seedDemo(db, NOW);

  assert.deepEqual(await existing(demoPaths()), demoPaths());
  const event = (await db.collection("events").where("isAssociationEvent", "==", true).get()).docs[0];
  assert.ok(event.get("location") instanceof GeoPoint);
  assert.ok(event.get("startTime") instanceof Timestamp);
  assert.ok(event.get("startTime").toDate() > NOW);
  assert.ok((await db.doc("associations/demo-association").get()).get("createdAt") instanceof Timestamp);
  const group = (await db.collectionGroup("groups").limit(1).get()).docs[0];
  assert.ok(group.get("createdAt") instanceof Timestamp);
});

test("the map's query finds the eight public events", async () => {
  await seedDemo(db, NOW);

  const upcoming = await db
    .collection("events")
    .where("isPrivate", "==", false)
    .where("startTime", ">=", Timestamp.fromDate(NOW))
    .get();
  assert.equal(upcoming.docs.filter((doc) => doc.id.startsWith("demo-")).length, 8);
});

test("seeding again updates the same documents instead of adding more", async () => {
  await seedDemo(db, NOW);
  const later = new Date(NOW.getTime() + 30 * 24 * 60 * 60 * 1000);
  await seedDemo(db, later);

  assert.deepEqual(await existing(demoPaths()), demoPaths());
  const events = await db.collection("events").get();
  assert.equal(events.size, 8 + 1);
  for (const doc of events.docs.filter((doc) => doc.id.startsWith("demo-"))) {
    assert.ok(doc.get("startTime").toDate() > later, `${doc.id} kept the earlier date`);
  }
});

test("seeding leaves every other document alone", async () => {
  await seedDemo(db, NOW);

  assert.deepEqual(await existing(OTHERS), OTHERS);
  for (const path of OTHERS) assert.deepEqual((await db.doc(path).get()).data(), { kept: true });
});

test("removing deletes every demo document and nothing else", async () => {
  await seedDemo(db, NOW);
  await removeDemo(db);

  assert.deepEqual(await existing(demoPaths()), []);
  assert.deepEqual(await existing(OTHERS), OTHERS);
});

test("removing when nothing was seeded succeeds and changes nothing", async () => {
  await removeDemo(db);

  assert.deepEqual(await existing(demoPaths()), []);
  assert.deepEqual(await existing(OTHERS), OTHERS);
});
