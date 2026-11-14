// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372; never merged).
// PROTOTYPE for #372 (never merged): what the Admin SDK code must do to attendeeCount, against the
// Firestore emulator: deleteAccount's registration cleanup, the one-off recount, and the existing
// deleteEventRegistrations logic (./registrations.js, unchanged; REGISTRATIONS_JS overrides the
// path) next to them. Reconstructed by the first reviewer from the author's prototype.
"use strict";
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { deleteRegistrationsOfDeletedEvent } = require(process.env.REGISTRATIONS_JS || "./registrations");

const PROJECT_ID = "demo-polysocial";
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;
const ROOT = `projects/${PROJECT_ID}/databases/(default)/documents`;
let app;
let db;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, "capacity-admin-prototype");
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

const MAX_BATCH_WRITES = 500;

/**
 * deleteAccount's step as #373 would write it: each registration is deleted in its own
 * transaction, which lowers its event's attendeeCount only if it really deletes the registration
 * and the event still exists with a counter above 0. [beforeEachDelete] lets a test fail midway.
 */
async function deleteUserRegistrations(db, uid, beforeEachDelete = async () => {}) {
  const query = db.collectionGroup("attendees").where("uid", "==", uid).limit(MAX_BATCH_WRITES);
  for (;;) {
    const snapshot = await query.get();
    if (snapshot.empty) return;
    for (const registration of snapshot.docs) {
      await beforeEachDelete(registration.ref.path);
      const event = registration.ref.parent.parent;
      await db.runTransaction(async (transaction) => {
        const [current, stored] = await transaction.getAll(registration.ref, event);
        if (!current.exists) return;
        transaction.delete(registration.ref);
        const count = stored.exists ? stored.get("attendeeCount") : null;
        if (Number.isInteger(count) && count > 0) transaction.update(event, { attendeeCount: count - 1 });
      });
    }
  }
}

/**
 * The one-off recount: sets every event's attendeeCount to the number of its registrations, each
 * event in its own transaction. It always writes the count, even when the stored value reads equal:
 * Node reads a counter stored as 3.0 as 3, and only a write stores it as a whole number again (a
 * JavaScript whole number is written as an integer). `changed` counts the events whose value
 * differed, so running it again writes the same counts and reports none, or repairs a drifted one.
 */
async function recountAttendees(db) {
  const events = await db.collection("events").select().get();
  let changed = 0;
  for (const event of events.docs) {
    changed += await db.runTransaction(async (transaction) => {
      const stored = await transaction.get(event.ref);
      if (!stored.exists) return 0;
      const counted = await transaction.get(event.ref.collection("attendees").count());
      const count = counted.data().count;
      transaction.update(event.ref, { attendeeCount: count });
      return stored.get("attendeeCount") === count ? 0 : 1;
    });
  }
  return { events: events.size, changed };
}

async function seed(eventId, { count, attendees, ...fields }) {
  const data = { title: eventId, ...fields };
  if (count !== undefined) data.attendeeCount = count;
  await db.doc(`events/${eventId}`).set(data);
  for (const uid of attendees) {
    await db.doc(`events/${eventId}/attendees/${uid}`).set({ uid, registeredAt: new Date(0) });
  }
}

async function state(eventId) {
  const event = await db.doc(`events/${eventId}`).get();
  const attendees = await db.collection(`events/${eventId}/attendees`).get();
  return {
    count: event.exists ? (event.get("attendeeCount") ?? "missing") : "no event",
    attendees: attendees.docs.map((attendee) => attendee.id).sort(),
  };
}

test("A1 deleteAccount lowers the counter of each event it removes a registration from", async () => {
  await seed("a", { count: 3, attendees: ["gone", "u1", "u2"] });
  await seed("b", { count: 1, attendees: ["gone"] });
  await seed("c", { count: 2, attendees: ["u1", "u2"] });
  await deleteUserRegistrations(db, "gone");
  assert.deepEqual(await state("a"), { count: 2, attendees: ["u1", "u2"] });
  assert.deepEqual(await state("b"), { count: 0, attendees: [] });
  assert.deepEqual(await state("c"), { count: 2, attendees: ["u1", "u2"] });
});

test("A2 a retry after a failure midway doesn't lower a counter twice", async () => {
  await seed("a", { count: 3, attendees: ["gone", "u1", "u2"] });
  await seed("b", { count: 2, attendees: ["gone", "u1"] });
  let calls = 0;
  await assert.rejects(deleteUserRegistrations(db, "gone", async () => {
    if (++calls === 2) throw new Error("backend unavailable");
  }));
  const partial = [await state("a"), await state("b")];
  assert.equal(partial.filter((event) => !event.attendees.includes("gone")).length, 1);
  await deleteUserRegistrations(db, "gone");
  await deleteUserRegistrations(db, "gone");
  assert.deepEqual(await state("a"), { count: 2, attendees: ["u1", "u2"] });
  assert.deepEqual(await state("b"), { count: 1, attendees: ["u1"] });
});

test("A3 a registration of a deleted event, or of an event without a counter or at 0, is deleted alone", async () => {
  await db.doc("events/deleted/attendees/gone").set({ uid: "gone", registeredAt: new Date(0) });
  await seed("legacy", { attendees: ["gone", "u1"] });
  await seed("zero", { count: 0, attendees: ["gone"] });
  await deleteUserRegistrations(db, "gone");
  assert.deepEqual(await state("deleted"), { count: "no event", attendees: [] });
  assert.deepEqual(await state("legacy"), { count: "missing", attendees: ["u1"] });
  assert.deepEqual(await state("zero"), { count: 0, attendees: [] });
});

test("A4 a registration the student or the trigger deleted in between isn't counted twice", async () => {
  await seed("a", { count: 3, attendees: ["gone", "u1", "u2"] });
  // The student's own cancellation lands after the query and before the transaction.
  await deleteUserRegistrations(db, "gone", async (path) => {
    await db.runTransaction(async (transaction) => {
      transaction.delete(db.doc(path));
      transaction.update(db.doc("events/a"), { attendeeCount: 2 });
    });
  });
  assert.deepEqual(await state("a"), { count: 2, attendees: ["u1", "u2"] });
});

test("A5 the trigger of a deleted event and deleteAccount don't disturb each other", async () => {
  await seed("a", { count: 2, attendees: ["gone", "u1"] });
  await seed("b", { count: 2, attendees: ["gone", "u1"] });
  await db.doc("events/a").delete();
  const logger = { error: () => assert.fail("the trigger failed") };
  await Promise.all([
    deleteRegistrationsOfDeletedEvent({ eventId: "a", db, logger }),
    deleteUserRegistrations(db, "gone"),
  ]);
  assert.deepEqual(await state("a"), { count: "no event", attendees: [] });
  assert.deepEqual(await state("b"), { count: 1, attendees: ["u1"] });
});

test("A6 the trigger keeps a rewritten event's registrations and its counter (the demo seed)", async () => {
  await seed("demo-event", { count: 2, attendees: ["demo-1", "demo-2"] });
  const logger = { error: () => assert.fail("the trigger failed") };
  // The trigger of the earlier deletion runs after the event was written back.
  await deleteRegistrationsOfDeletedEvent({ eventId: "demo-event", db, logger });
  assert.deepEqual(await state("demo-event"), { count: 2, attendees: ["demo-1", "demo-2"] });
});

test("B1 the recount gives every event the number of its registrations, and running it again changes nothing", async () => {
  await seed("legacy", { attendees: ["u1", "u2", "u3"] });
  await seed("empty", { attendees: [] });
  await seed("drifted", { count: 5, attendees: ["u1"] });
  await seed("right", { count: 2, attendees: ["u1", "u2"] });
  await db.doc("events/deleted/attendees/u1").set({ uid: "u1", registeredAt: new Date(0) });
  assert.deepEqual(await recountAttendees(db), { events: 4, changed: 3 });
  assert.deepEqual(await state("legacy"), { count: 3, attendees: ["u1", "u2", "u3"] });
  assert.deepEqual(await state("empty"), { count: 0, attendees: [] });
  assert.deepEqual(await state("drifted"), { count: 1, attendees: ["u1"] });
  assert.deepEqual(await state("right"), { count: 2, attendees: ["u1", "u2"] });
  assert.deepEqual(await state("deleted"), { count: "no event", attendees: ["u1"] });
  assert.deepEqual(await recountAttendees(db), { events: 4, changed: 0 });
});

test("B2 the recount leaves every other field of the event alone", async () => {
  await seed("legacy", { capacity: 20, isPrivate: false, attendees: ["u1"] });
  await recountAttendees(db);
  assert.deepEqual((await db.doc("events/legacy").get()).data(), {
    title: "legacy", capacity: 20, isPrivate: false, attendeeCount: 1,
  });
});

/** The stored attendeeCount of [path] as the REST API shows it, with its type. */
async function rawCount(path) {
  const response = await fetch(`http://${FIRESTORE_HOST}/v1/${ROOT}/${path}`, {
    headers: { Authorization: "Bearer owner" },
  });
  if (!response.ok) throw new Error(`Couldn't read ${path}: HTTP ${response.status}`);
  return (await response.json()).fields.attendeeCount;
}

test("B3 the recount rewrites a counter stored as a decimal (3.0) as the whole number 3", async () => {
  // Only a hand edit or an Admin client in another language stores 3.0; written here over REST.
  const response = await fetch(`http://${FIRESTORE_HOST}/v1/${ROOT}:commit`, {
    method: "POST",
    headers: { Authorization: "Bearer owner", "Content-Type": "application/json" },
    body: JSON.stringify({ writes: [{ update: {
      name: `${ROOT}/events/decimal`,
      fields: { title: { stringValue: "decimal" }, attendeeCount: { doubleValue: 3 } },
    } }] }),
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
