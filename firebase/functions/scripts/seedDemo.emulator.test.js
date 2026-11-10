// Contributors: Claude (the demo seed against the Firestore emulator, written test-first, #243;
// trimmed after review to what seeding and removing touch).
"use strict";

// Seeds and removes the demo data in the Firestore emulator, with the Admin SDK as the script does.
// Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore "node firebase/functions/scripts/seedDemo.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
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

/** A real student going to the demo jam session and joining its group, as the app writes it. */
async function realStudentJoinsTheJam() {
  await db.doc("events/demo-event-jam/attendees/real-student").set({ uid: "real-student" });
  await db.doc("events/demo-event-jam/groupMembers/real-student").set({ groupId: "demo-group-jam" });
  await db.doc("events/demo-event-jam/groups/demo-group-jam").update({
    memberUids: ["demo-student-4", "demo-student-1", "demo-student-5", "real-student"],
  });
  await db.doc("events/demo-event-jam/groups/real-group").set({ memberUids: ["real-student"] });
}

const REAL_STUDENT_ON_THE_JAM = [
  "events/demo-event-jam/attendees/real-student",
  "events/demo-event-jam/groupMembers/real-student",
  "events/demo-event-jam/groups/real-group",
];

test("removing also deletes what real students added under demo events", async () => {
  await seedDemo(db, NOW);
  await realStudentJoinsTheJam();

  await removeDemo(db);

  assert.deepEqual(await existing([...demoPaths(), ...REAL_STUDENT_ON_THE_JAM]), []);
  assert.deepEqual(await existing(OTHERS), OTHERS);
});

test("seeding again resets the demo events, dropping students who went or joined", async () => {
  await seedDemo(db, NOW);
  await realStudentJoinsTheJam();

  await seedDemo(db, NOW);

  assert.deepEqual(await existing(demoPaths()), demoPaths());
  assert.deepEqual(await existing(REAL_STUDENT_ON_THE_JAM), []);
  assert.deepEqual((await db.doc("events/demo-event-jam/groups/demo-group-jam").get()).get("memberUids"), [
    "demo-student-4",
    "demo-student-1",
    "demo-student-5",
  ]);
  // Another event's registration of the same student is theirs, not the demo's.
  assert.deepEqual(await existing(OTHERS), OTHERS);
});
