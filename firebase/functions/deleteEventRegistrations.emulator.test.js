// Contributors: Claude (Opus 5.5, Anthropic) drafted the emulator test of the trigger deleting a
// deleted event's registrations, #275; an event written again, after review.
// Contributors: Claude (Opus 5.5, Anthropic) moved its setup to testing/triggerEmulator.js, #382.
// Contributors: Claude (Opus 5.5, Anthropic) (check-ins and the entrance code deleted with the
// event, #472); Konstantinos Perdikeas (directed, reviewed and owns it).
"use strict";

// Deletes events in the Firestore emulator and waits for the deleteEventRegistrations trigger,
// running in the Functions emulator. Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node --test --test-concurrency=1 firebase/functions/*.emulator.test.js"
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { pathsIn, useTriggerEmulators, waitFor, waitUntil } = require("./testing/triggerEmulator");

const database = useTriggerEmulators("deleteEventRegistrations");

/** Stores a fictional event and a registration for it from each of `uids`. */
async function seedEvent(eventId, uids) {
  const db = database();
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
function registrations() {
  return pathsIn(database(), "attendees");
}

/** Waits until the registrations left are `expected`, or fails after the helper's timeout. */
function waitForRegistrations(expected) {
  return waitFor(registrations, expected);
}

test("deleting an event deletes its registrations, other events' are kept", async () => {
  await seedEvent("deleted", ["u1", "u2"]);
  await seedEvent("kept", ["u1"]);

  await database().doc("events/deleted").delete();

  await waitForRegistrations(["events/kept/attendees/u1"]);
  assert.equal((await database().doc("events/kept").get()).exists, true);
});

test("an event with more registrations than one batch holds loses them all", async () => {
  const uids = Array.from({ length: 501 }, (_, i) => `u${i}`);
  await seedEvent("crowded", uids);
  await database().doc("events/crowded").delete();
  await waitForRegistrations([]);
});

/** Stores a check-in of each of `uids` and the entrance code under `eventId`, as the app writes them. */
async function seedCheckIns(eventId, uids) {
  const db = database();
  for (const uid of uids) {
    await db.doc(`events/${eventId}/checkIns/${uid}`).set({
      uid, checkedInAt: new Date(), method: "location", expiresAt: new Date(),
    });
  }
  await db.doc(`events/${eventId}/checkInCode/current`).set({
    token: "a".repeat(22), rotatedAt: new Date(), expiresAt: new Date(),
  });
}

/** The paths of every check-in and entrance code left, sorted. */
async function attendanceLeft() {
  return [
    ...(await pathsIn(database(), "checkIns")),
    ...(await pathsIn(database(), "checkInCode")),
  ];
}

test("deleting an event deletes its check-ins and entrance code, other events' are kept (#472)", async () => {
  await seedEvent("cancelled", ["u1", "u2"]);
  await seedCheckIns("cancelled", ["u1", "u2"]);
  await seedEvent("still-on", ["u1"]);
  await seedCheckIns("still-on", ["u1"]);

  await database().doc("events/cancelled").delete();

  await waitFor(attendanceLeft, [
    "events/still-on/checkIns/u1",
    "events/still-on/checkInCode/current",
  ]);
  await waitForRegistrations(["events/still-on/attendees/u1"]);
});

// The demo seed script deletes its events and writes them back under the same IDs, with new
// registrations, in one batch.
test("an event written again under its ID keeps its new registrations", async () => {
  await seedEvent("again", ["u1"]);
  await seedEvent("gone", ["u1"]);

  await database().doc("events/again").delete();
  await seedEvent("again", ["u2"]);
  await database().doc("events/gone").delete();

  // Wait for the later deletion's trigger, then give the earlier one a margin to run too. The
  // old registration may be gone or kept, depending on whether that trigger ran before the event
  // came back; the new one must stay.
  await waitUntil(registrations, (left) => !left.some((path) => path.startsWith("events/gone/")));
  await new Promise((resolve) => setTimeout(resolve, 1000));
  assert.ok((await registrations()).includes("events/again/attendees/u2"));
});
