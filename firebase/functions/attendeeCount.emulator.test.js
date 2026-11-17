// Contributors: Claude (Opus 5.5, Anthropic) (the attendeeCount an account deletion lowers, against
// the Firestore emulator, from #372's prototype, #405).
"use strict";

// Runs registrations.js against the Firestore emulator, with the Admin SDK as the functions do. Run
// from the repository root with the other emulator tests (see the README, "Cloud Functions"):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node --test --test-concurrency=1 firebase/functions/*.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const {
  deleteCountedRegistration,
  deleteRegistrationsOfDeletedEvent,
  deleteUserRegistrations,
} = require("./registrations");

const PROJECT_ID = "demo-polysocial";
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;

if (!FIRESTORE_HOST) {
  throw new Error(
    "FIRESTORE_EMULATOR_HOST is not set: run this test inside " +
      '`firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "..."`.',
  );
}

let app;
let db;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, "attendee-count-test");
  db = getFirestore(app);
});

after(() => deleteApp(app));

beforeEach(async () => {
  const response = await fetch(
    `http://${FIRESTORE_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
    { method: "DELETE" },
  );
  if (!response.ok) throw new Error(`Couldn't clear the Firestore emulator: HTTP ${response.status}`);
});

/** Stores the event [eventId] with [count] as attendeeCount (none if undefined) and [attendees]. */
async function seed(eventId, { count, attendees }) {
  const data = { title: eventId };
  if (count !== undefined) data.attendeeCount = count;
  await db.doc(`events/${eventId}`).set(data);
  for (const uid of attendees) {
    await db.doc(`events/${eventId}/attendees/${uid}`).set({ uid, registeredAt: new Date(0) });
  }
}

/** The event's stored attendeeCount ("missing", or "no event") and its registrations, sorted. */
async function state(eventId) {
  const event = await db.doc(`events/${eventId}`).get();
  const attendees = await db.collection(`events/${eventId}/attendees`).get();
  return {
    count: event.exists ? (event.get("attendeeCount") ?? "missing") : "no event",
    attendees: attendees.docs.map((attendee) => attendee.id).sort(),
  };
}

const logger = { error: () => assert.fail("the trigger failed") };

test("deleting an account's registrations lowers each of their events' counter by one", async () => {
  await seed("a", { count: 3, attendees: ["gone", "u1", "u2"] });
  await seed("b", { count: 1, attendees: ["gone"] });
  await seed("c", { count: 2, attendees: ["u1", "u2"] });

  await deleteUserRegistrations(db, "gone");

  assert.deepEqual(await state("a"), { count: 2, attendees: ["u1", "u2"] });
  assert.deepEqual(await state("b"), { count: 0, attendees: [] });
  assert.deepEqual(await state("c"), { count: 2, attendees: ["u1", "u2"] });
});

test("running it again, as a retry does, lowers nothing twice", async () => {
  await seed("a", { count: 3, attendees: ["gone", "u1", "u2"] });
  await seed("b", { count: 2, attendees: ["gone", "u1"] });

  await deleteUserRegistrations(db, "gone");
  await deleteUserRegistrations(db, "gone");

  assert.deepEqual(await state("a"), { count: 2, attendees: ["u1", "u2"] });
  assert.deepEqual(await state("b"), { count: 1, attendees: ["u1"] });
});

test("a deleted event's, a counterless event's or a zero counter's registration is deleted alone", async () => {
  await db.doc("events/deleted/attendees/gone").set({ uid: "gone", registeredAt: new Date(0) });
  await seed("legacy", { attendees: ["gone", "u1"] });
  await seed("zero", { count: 0, attendees: ["gone"] });

  await deleteUserRegistrations(db, "gone");

  assert.deepEqual(await state("deleted"), { count: "no event", attendees: [] });
  assert.deepEqual(await state("legacy"), { count: "missing", attendees: ["u1"] });
  assert.deepEqual(await state("zero"), { count: 0, attendees: [] });
});

test("a registration the student already cancelled isn't counted again", async () => {
  await seed("a", { count: 3, attendees: ["gone", "u1", "u2"] });
  const registration = db.doc("events/a/attendees/gone");
  // The student's own cancellation, as the app makes it: the registration and the counter together.
  await db.runTransaction(async (transaction) => {
    transaction.delete(registration);
    transaction.update(db.doc("events/a"), { attendeeCount: 2 });
  });

  // deleteAccount's transaction for the registration its query found just before.
  await deleteCountedRegistration(db, registration);

  assert.deepEqual(await state("a"), { count: 2, attendees: ["u1", "u2"] });
});

test("a deleted event's trigger and an account deletion running together don't disturb each other", async () => {
  await seed("a", { count: 2, attendees: ["gone", "u1"] });
  await seed("b", { count: 2, attendees: ["gone", "u1"] });
  await db.doc("events/a").delete();

  await Promise.all([
    deleteRegistrationsOfDeletedEvent({ eventId: "a", db, logger }),
    deleteUserRegistrations(db, "gone"),
  ]);

  assert.deepEqual(await state("a"), { count: "no event", attendees: [] });
  assert.deepEqual(await state("b"), { count: 1, attendees: ["u1"] });
});

test("the trigger keeps a rewritten event's registrations and its counter, as the demo seed needs", async () => {
  await seed("demo-event", { count: 2, attendees: ["demo-1", "demo-2"] });

  // The trigger of the event's earlier deletion runs after the seed wrote it back.
  await deleteRegistrationsOfDeletedEvent({ eventId: "demo-event", db, logger });

  assert.deepEqual(await state("demo-event"), { count: 2, attendees: ["demo-1", "demo-2"] });
});
