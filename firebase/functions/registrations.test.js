// Contributors: Claude (Opus 5.5, Anthropic) drafted the tests of deleting registrations with their
// event and account, #275; an event written again keeps its registrations, after review.
// Contributors: Claude (Opus 5.5, Anthropic) tested the attendeeCount an account deletion lowers,
// #405.
// Contributors: Claude (Opus 5.5, Anthropic) moved the fakes to testing/fakeFirestore.js, #382;
// an event re-created by someone else loses its registrations, after review; a deletion without
// its document, after review.
// Contributors: Claude (Opus 5.5, Anthropic) (check-ins deleted with the account and the event,
// #472); Konstantinos Perdikeas (directed, reviewed and owns it).
"use strict";

// Unit tests with fakes, no emulator: node firebase/functions/registrations.test.js
const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  MAX_BATCH_WRITES,
  TRIGGER_REGION,
  deleteCountedRegistration,
  deleteRegistrationsOfDeletedEvent,
  deleteUserCheckIns,
  deleteUserRegistrations,
} = require("./registrations");
const { backendError, fakeFirestore, fakeLogger } = require("./testing/fakeFirestore");

/** The deleted event's data, as the trigger gets it: its organizer's UID. */
const DELETED = { createdBy: "organizer" };

test("the trigger runs in a region Eventarc serves for Firestore's eur3", () => {
  assert.ok(["europe-west1", "europe-west4"].includes(TRIGGER_REGION));
});

test("a deleted event's registrations are deleted, other events' are kept", async () => {
  const { db, documents } = fakeFirestore([
    "events/e1/attendees/u1",
    "events/e1/attendees/u2",
    "events/e2/attendees/u1",
  ]);
  const { logger, logs } = fakeLogger();

  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger });

  assert.deepEqual([...documents], ["events/e2/attendees/u1"]);
  assert.deepEqual(logs, []);
});

// #472: a deleted event's check-ins and entrance code go with it; nobody could read them any more.
test("a deleted event's check-ins and entrance code are deleted too, other events' are kept", async () => {
  const { db, documents } = fakeFirestore([
    "events/e1/attendees/u1",
    "events/e1/checkIns/u1",
    "events/e1/checkIns/u2",
    "events/e1/checkInCode/current",
    "events/e2/checkIns/u1",
    "events/e2/checkInCode/current",
  ]);

  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  assert.deepEqual([...documents], ["events/e2/checkIns/u1", "events/e2/checkInCode/current"]);
});

test("an event its organizer wrote again keeps its check-ins and entrance code", async () => {
  const paths = ["events/e1", "events/e1/checkIns/u1", "events/e1/checkInCode/current"];
  const { db, documents, state } = fakeFirestore(paths, {}, { "events/e1": DELETED });

  await deleteRegistrationsOfDeletedEvent({
    eventId: "e1",
    deleted: DELETED,
    db,
    logger: fakeLogger().logger,
  });

  assert.deepEqual([...documents], paths);
  assert.equal(state.commits, 0);
});

test("a failure before the check-ins is retried, and the retry deletes them", async () => {
  const { db, documents, state } = fakeFirestore([
    "events/e1/attendees/u1",
    "events/e1/checkIns/u1",
    "events/e1/checkInCode/current",
  ]);
  const { logger, logs } = fakeLogger();
  // The registrations are deleted, then the check-ins' commit fails.
  state.onCommit = () => {
    state.onCommit = null;
    state.failCommit = backendError(14);
  };

  await assert.rejects(deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger }), {
    code: 14,
  });
  assert.deepEqual([...documents], ["events/e1/checkIns/u1", "events/e1/checkInCode/current"]);
  assert.deepEqual(logs, [["error", "deleteEventRegistrations failed", { code: 14 }]]);

  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger });
  assert.equal(documents.size, 0);
});

test("an event without registrations deletes nothing and succeeds", async () => {
  const { db, documents, state } = fakeFirestore(["events/e2/attendees/u1"]);
  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });
  assert.equal(state.commits, 0);
  assert.equal(documents.size, 1);
});

test("more registrations than one batch holds are deleted in several batches", async () => {
  const count = MAX_BATCH_WRITES * 2 + 1;
  const paths = Array.from({ length: count }, (_, i) => `events/e1/attendees/u${i}`);
  const { db, documents, state } = fakeFirestore(paths);

  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  assert.equal(documents.size, 0);
  assert.equal(state.commits, 3);
});

test("a registration written while deleting is deleted too", async () => {
  const { db, documents, state } = fakeFirestore(["events/e1/attendees/u1"]);
  state.onCommit = (stored) => {
    state.onCommit = null;
    stored.add("events/e1/attendees/u2");
  };

  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  assert.equal(documents.size, 0);
});

test("a failure is thrown again, for the trigger's retry, and logs only its code", async () => {
  const { db, documents, state } = fakeFirestore(["events/e1/attendees/u1"]);
  const { logger, logs } = fakeLogger();
  state.failCommit = backendError(14);

  await assert.rejects(deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger }), {
    code: 14,
  });
  assert.equal(documents.size, 1);
  assert.deepEqual(logs, [["error", "deleteEventRegistrations failed", { code: 14 }]]);
  const logged = JSON.stringify(logs);
  assert.ok(!logged.includes("e1") && !logged.includes("u1"), "no event or uid is logged");

  // The retry finishes the job.
  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger });
  assert.equal(documents.size, 0);
});

test("a failure without a code logs a null code", async () => {
  const { db, state } = fakeFirestore(["events/e1/attendees/u1"]);
  const { logger, logs } = fakeLogger();
  state.failCommit = new TypeError("unexpected");

  await assert.rejects(deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger }), TypeError);
  assert.deepEqual(logs, [["error", "deleteEventRegistrations failed", { code: null }]]);
});

// The demo seed script deletes its events and writes them back under the same IDs, with the same
// organizers and new registrations, before this trigger may run.
test("an event its organizer wrote again before the trigger runs keeps its registrations", async () => {
  const paths = ["events/e1", "events/e1/attendees/u1"];
  const { db, documents, state } = fakeFirestore(paths, {}, { "events/e1": DELETED });

  await deleteRegistrationsOfDeletedEvent({
    eventId: "e1",
    deleted: DELETED,
    db,
    logger: fakeLogger().logger,
  });

  assert.deepEqual([...documents], paths);
  assert.equal(state.commits, 0);
});

// A client chooses an event's ID, and the rules make its createdBy the creator's UID.
test("an event another student created again under its ID still loses its registrations", async () => {
  const { db, documents } = fakeFirestore(
    ["events/e1", "events/e1/attendees/u1", "events/e1/attendees/u2"],
    {},
    { "events/e1": { createdBy: "another-student" } },
  );

  await deleteRegistrationsOfDeletedEvent({
    eventId: "e1",
    deleted: DELETED,
    db,
    logger: fakeLogger().logger,
  });

  assert.deepEqual([...documents], ["events/e1"]);
});

// Firestore always sends the deleted document; without it the creator is unknown, so the cleanup
// deletes rather than keeps, even under an event written back by its organizer.
test("a deletion that came without the deleted event still deletes its registrations", async () => {
  const { db, documents } = fakeFirestore(
    ["events/e1", "events/e1/attendees/u1"],
    {},
    { "events/e1": DELETED },
  );

  await deleteRegistrationsOfDeletedEvent({
    eventId: "e1",
    deleted: undefined,
    db,
    logger: fakeLogger().logger,
  });

  assert.deepEqual([...documents], ["events/e1"]);
});

test("an event its organizer wrote again while deleting keeps the registrations written with it", async () => {
  const count = MAX_BATCH_WRITES + 1;
  const paths = Array.from({ length: count }, (_, i) => `events/e1/attendees/old${i}`);
  const { db, documents, state } = fakeFirestore(paths);
  state.onCommit = (stored) => {
    state.onCommit = null;
    stored.add("events/e1");
    state.fields.set("events/e1", DELETED);
    stored.add("events/e1/attendees/new");
  };

  await deleteRegistrationsOfDeletedEvent({
    eventId: "e1",
    deleted: DELETED,
    db,
    logger: fakeLogger().logger,
  });

  // The first batch went before the event came back; the rest now belongs to the new event.
  assert.equal(state.commits, 1);
  assert.ok(documents.has("events/e1/attendees/new"));
  assert.equal(documents.size, 3);
});

test("a user's registrations are deleted across events, other users' are kept", async () => {
  const { db, documents } = fakeFirestore([
    "events/e1/attendees/u1",
    "events/e2/attendees/u1",
    "events/e1/attendees/u2",
  ]);

  await deleteUserRegistrations(db, "u1");

  assert.deepEqual([...documents], ["events/e1/attendees/u2"]);
});

test("each event the user was going to counts one student less", async () => {
  const { db, state } = fakeFirestore(
    [
      "events/e1",
      "events/e1/attendees/u1",
      "events/e1/attendees/u2",
      "events/e2",
      "events/e2/attendees/u1",
      "events/e3",
      "events/e3/attendees/u2",
    ],
    { "events/e1": 2, "events/e2": 1, "events/e3": 1 },
  );

  await deleteUserRegistrations(db, "u1");

  assert.deepEqual(Object.fromEntries(state.counts), {
    "events/e1": 1,
    "events/e2": 0,
    "events/e3": 1,
  });
});

test("a registration is deleted alone when its event is gone, has no counter, or is at 0", async () => {
  const { db, documents, state } = fakeFirestore(
    [
      "events/gone/attendees/u1",
      "events/legacy",
      "events/legacy/attendees/u1",
      "events/zero",
      "events/zero/attendees/u1",
    ],
    { "events/zero": 0 },
  );

  await deleteUserRegistrations(db, "u1");

  assert.deepEqual([...documents], ["events/legacy", "events/zero"]);
  assert.deepEqual(Object.fromEntries(state.counts), { "events/zero": 0 });
});

test("a counter that isn't a whole number is left as it is", async () => {
  const { db, state } = fakeFirestore(
    ["events/e1", "events/e1/attendees/u1", "events/e2", "events/e2/attendees/u1"],
    { "events/e1": 2.5, "events/e2": "3" },
  );

  await deleteUserRegistrations(db, "u1");

  assert.deepEqual(Object.fromEntries(state.counts), { "events/e1": 2.5, "events/e2": "3" });
});

test("a registration the student cancelled in between isn't counted twice", async () => {
  const { db, documents, state } = fakeFirestore(
    ["events/e1", "events/e1/attendees/u1", "events/e1/attendees/u2"],
    { "events/e1": 2 },
  );
  // The student's own cancellation (or the event's trigger) lands after the query, before the
  // transaction reads the registration: it already lowered the counter.
  state.onTransaction = (stored) => {
    state.onTransaction = null;
    stored.delete("events/e1/attendees/u1");
    state.counts.set("events/e1", 1);
  };

  await deleteUserRegistrations(db, "u1");

  assert.equal(state.counts.get("events/e1"), 1);
  assert.deepEqual([...documents], ["events/e1", "events/e1/attendees/u2"]);
});

test("a retry after a failure midway lowers each counter once", async () => {
  const { db, documents, state } = fakeFirestore(
    ["events/e1", "events/e1/attendees/u1", "events/e2", "events/e2/attendees/u1"],
    { "events/e1": 3, "events/e2": 2 },
  );
  // The first event's transaction commits, the second one's fails.
  state.onCommit = () => {
    state.onCommit = null;
    state.failCommit = backendError(14);
  };

  await assert.rejects(deleteUserRegistrations(db, "u1"), { code: 14 });
  assert.equal(documents.size, 3);
  await deleteUserRegistrations(db, "u1");
  await deleteUserRegistrations(db, "u1");

  assert.deepEqual([...documents], ["events/e1", "events/e2"]);
  assert.deepEqual(Object.fromEntries(state.counts), { "events/e1": 2, "events/e2": 1 });
});

test("more registrations than one query returns are all deleted", async () => {
  const count = MAX_BATCH_WRITES + 2;
  const paths = Array.from({ length: count }, (_, i) => `events/e${i}/attendees/u1`);
  const { db, documents } = fakeFirestore(paths);

  await deleteUserRegistrations(db, "u1");

  assert.equal(documents.size, 0);
});

// #472: the account's check-ins, found like its registrations, by the collection-group query on uid.
test("a user's check-ins are deleted across events, other users' are kept", async () => {
  const { db, documents } = fakeFirestore([
    "events/e1/checkIns/u1",
    "events/e2/checkIns/u1",
    "events/e1/checkIns/u2",
    "events/e1/attendees/u1",
    "events/e1/checkInCode/current",
  ]);

  await deleteUserCheckIns(db, "u1");

  assert.deepEqual([...documents], [
    "events/e1/checkIns/u2",
    "events/e1/attendees/u1",
    "events/e1/checkInCode/current",
  ]);
});

test("more check-ins than one batch holds are all deleted", async () => {
  const count = MAX_BATCH_WRITES + 2;
  const paths = Array.from({ length: count }, (_, i) => `events/e${i}/checkIns/u1`);
  const { db, documents, state } = fakeFirestore(paths);

  await deleteUserCheckIns(db, "u1");

  assert.equal(documents.size, 0);
  assert.equal(state.commits, 2);
});

test("a failed check-in deletion is thrown, and a retry finishes it", async () => {
  const { db, documents, state } = fakeFirestore(["events/e1/checkIns/u1"]);
  state.failCommit = backendError(14);

  await assert.rejects(deleteUserCheckIns(db, "u1"), { code: 14 });
  assert.equal(documents.size, 1);

  await deleteUserCheckIns(db, "u1");
  assert.equal(documents.size, 0);
});

test("deleting a registration that is already gone changes nothing", async () => {
  const { db, state } = fakeFirestore(["events/e1"], { "events/e1": 4 });

  await deleteCountedRegistration(db, {
    path: "events/e1/attendees/u1",
    parent: { parent: { path: "events/e1" } },
  });

  assert.equal(state.counts.get("events/e1"), 4);
  assert.equal(state.commits, 0);
});
