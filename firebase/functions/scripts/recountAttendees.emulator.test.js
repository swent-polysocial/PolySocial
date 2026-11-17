// Contributors: Claude (Opus 5.5, Anthropic) (the attendee recount against the Firestore emulator,
// from #372's prototype, #405).
"use strict";

// Runs the recount against the Firestore emulator, with the Admin SDK as the script does. Run from
// the repository root (see the README, "Recounting attendees"):
// firebase emulators:exec --project demo-polysocial --only firestore "node firebase/functions/scripts/recountAttendees.emulator.test.js"
// A "demo-" project never reaches a real Firebase project.
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { recountAttendees } = require("./recountAttendees");

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
  app = initializeApp({ projectId: PROJECT_ID }, "recount-attendees-test");
  db = getFirestore(app);
});

after(() => deleteApp(app));

beforeEach(async () => {
  const response = await fetch(`http://${FIRESTORE_HOST}/emulator/v1/${ROOT}`, {
    method: "DELETE",
  });
  if (!response.ok) throw new Error(`Couldn't clear the Firestore emulator: HTTP ${response.status}`);
});

/** Stores the event [eventId] with [fields], [count] as attendeeCount if given, and [attendees]. */
async function seed(eventId, { count, attendees, ...fields }) {
  const data = { title: eventId, ...fields };
  if (count !== undefined) data.attendeeCount = count;
  await db.doc(`events/${eventId}`).set(data);
  for (const uid of attendees) {
    await db.doc(`events/${eventId}/attendees/${uid}`).set({ uid, registeredAt: new Date(0) });
  }
}

async function countOf(eventId) {
  const event = await db.doc(`events/${eventId}`).get();
  return event.exists ? (event.get("attendeeCount") ?? "missing") : "no event";
}

/** The stored attendeeCount of [path] as the REST API shows it, with its type. */
async function rawCount(path) {
  const response = await fetch(`http://${FIRESTORE_HOST}/v1/${ROOT}/${path}`, {
    headers: { Authorization: "Bearer owner" },
  });
  if (!response.ok) throw new Error(`Couldn't read ${path}: HTTP ${response.status}`);
  return (await response.json()).fields.attendeeCount;
}

test("every event gets the number of its registrations, and running it again changes nothing", async () => {
  await seed("legacy", { attendees: ["u1", "u2", "u3"] });
  await seed("empty", { attendees: [] });
  await seed("drifted", { count: 5, attendees: ["u1"] });
  await seed("right", { count: 2, attendees: ["u1", "u2"] });
  // Registrations left under a deleted event, which the recount must not bring back.
  await db.doc("events/deleted/attendees/u1").set({ uid: "u1", registeredAt: new Date(0) });

  assert.deepEqual(await recountAttendees(db), { events: 4, changed: 3 });
  assert.equal(await countOf("legacy"), 3);
  assert.equal(await countOf("empty"), 0);
  assert.equal(await countOf("drifted"), 1);
  assert.equal(await countOf("right"), 2);
  assert.equal(await countOf("deleted"), "no event");

  assert.deepEqual(await recountAttendees(db), { events: 4, changed: 0 });
});

test("every other field of the event is left alone", async () => {
  await seed("legacy", { capacity: 20, isPrivate: false, attendees: ["u1"] });

  await recountAttendees(db);

  assert.deepEqual((await db.doc("events/legacy").get()).data(), {
    title: "legacy",
    capacity: 20,
    isPrivate: false,
    attendeeCount: 1,
  });
});

test("a counter stored as a decimal (3.0) is written back as the whole number 3", async () => {
  // Only a hand edit or an Admin client in another language stores 3.0; written here over REST.
  const response = await fetch(`http://${FIRESTORE_HOST}/v1/${ROOT}:commit`, {
    method: "POST",
    headers: { Authorization: "Bearer owner", "Content-Type": "application/json" },
    body: JSON.stringify({
      writes: [
        {
          update: {
            name: `${ROOT}/events/decimal`,
            fields: { title: { stringValue: "decimal" }, attendeeCount: { doubleValue: 3 } },
          },
        },
      ],
    }),
  });
  if (!response.ok) throw new Error(`Couldn't seed the decimal counter: HTTP ${response.status}`);
  for (const uid of ["u1", "u2", "u3"]) {
    await db.doc(`events/decimal/attendees/${uid}`).set({ uid, registeredAt: new Date(0) });
  }
  assert.deepEqual(await rawCount("events/decimal"), { doubleValue: 3 });

  // Node reads 3.0 as 3, so nothing differed, but the count is written all the same.
  assert.deepEqual(await recountAttendees(db), { events: 1, changed: 0 });
  assert.deepEqual(await rawCount("events/decimal"), { integerValue: "3" });
});
