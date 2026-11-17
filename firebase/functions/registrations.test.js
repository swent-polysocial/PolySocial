// Contributors: Claude (Opus 5.5, Anthropic) drafted the tests of deleting registrations with their
// event and account, #275; an event written again keeps its registrations, after review.
// Contributors: Claude (Opus 5.5, Anthropic) tested the attendeeCount an account deletion lowers,
// #405.
"use strict";

// Unit tests with fakes, no emulator: node firebase/functions/registrations.test.js
const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  MAX_BATCH_WRITES,
  TRIGGER_REGION,
  deleteCountedRegistration,
  deleteRegistrationsOfDeletedEvent,
  deleteUserRegistrations,
} = require("./registrations");

/** An error as the Admin SDK reports it, with a gRPC status code. */
function backendError(code) {
  return Object.assign(new Error("raw backend failure for event-1 and student-1"), { code });
}

/**
 * A fake Firestore holding documents as paths: events `events/{eventId}` and registrations
 * `events/{eventId}/attendees/{uid}`, with each event's `attendeeCount` in `counts` (by path,
 * absent when the event has none). It answers an event's attendees and the collection-group query
 * on `uid`, with `limit`, and runs batches and transactions (reading an event, a query, or several
 * documents with `getAll`) whose deletes and updates apply together at commit. `commits` counts
 * the commits that wrote something; `failCommit` makes the next one throw; `onCommit` runs after
 * each commit, and `onTransaction` before each transaction's reads, to write in between.
 */
function fakeFirestore(paths, counts = {}) {
  const documents = new Set(paths);
  const state = {
    commits: 0,
    failCommit: null,
    onCommit: null,
    onTransaction: null,
    counts: new Map(Object.entries(counts)),
  };
  const ref = (path) => ({
    path,
    parent: { parent: { path: path.split("/").slice(0, 2).join("/") } },
  });
  const snapshot = (path) => ({
    exists: documents.has(path),
    get: (field) => (field === "attendeeCount" ? state.counts.get(path) : undefined),
  });
  const uidOf = (path) => path.split("/")[3];
  const query = (matches, max = Infinity) => ({
    limit: (n) => query(matches, n),
    async get() {
      const docs = [...documents].filter(matches).slice(0, max).map((path) => ({ ref: ref(path) }));
      return { empty: docs.length === 0, size: docs.length, docs };
    },
  });
  async function commit(deletes, updates = []) {
    assert.ok(deletes.length + updates.length <= MAX_BATCH_WRITES, "a commit holds 500 writes");
    if (state.failCommit) {
      const error = state.failCommit;
      state.failCommit = null;
      throw error;
    }
    for (const path of deletes) documents.delete(path);
    for (const [path, data] of updates) {
      assert.ok(documents.has(path), `update of a missing document ${path}`);
      state.counts.set(path, data.attendeeCount);
    }
    if (deletes.length + updates.length > 0) state.commits += 1;
    if (state.onCommit) state.onCommit(documents);
  }
  const db = {
    collection: (events) => ({
      doc: (eventId) => ({
        path: `${events}/${eventId}`,
        collection: (attendees) =>
          query((path) => path.startsWith(`${events}/${eventId}/${attendees}/`)),
      }),
    }),
    collectionGroup: (group) => ({
      where: (field, op, value) => {
        assert.equal(group, "attendees");
        assert.deepEqual([field, op], ["uid", "=="]);
        return query((path) => path.split("/")[2] === group && uidOf(path) === value);
      },
    }),
    batch() {
      const deletes = [];
      return {
        delete: (ref) => deletes.push(ref.path),
        commit: () => commit(deletes),
      };
    },
    async runTransaction(update) {
      if (state.onTransaction) state.onTransaction(documents);
      const deletes = [];
      const updates = [];
      const result = await update({
        // A document reference has a path; a query is read with get().
        get: async (target) => (target.path === undefined ? target.get() : snapshot(target.path)),
        getAll: async (...targets) => targets.map((target) => snapshot(target.path)),
        delete: (ref) => deletes.push(ref.path),
        update: (ref, data) => updates.push([ref.path, data]),
      });
      await commit(deletes, updates);
      return result;
    },
  };
  return { db, documents, state };
}

function fakeLogger() {
  const logs = [];
  const logger = {};
  for (const level of ["debug", "log", "info", "warn", "error"]) {
    logger[level] = (...entry) => logs.push([level, ...entry]);
  }
  return { logger, logs };
}

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

// The demo seed script deletes its events and writes them back under the same IDs, with new
// registrations, before this trigger may run.
test("an event written again before the trigger runs keeps its registrations", async () => {
  const { db, documents, state } = fakeFirestore(["events/e1", "events/e1/attendees/u1"]);

  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  assert.deepEqual([...documents], ["events/e1", "events/e1/attendees/u1"]);
  assert.equal(state.commits, 0);
});

test("an event written again while deleting keeps the registrations written with it", async () => {
  const count = MAX_BATCH_WRITES + 1;
  const paths = Array.from({ length: count }, (_, i) => `events/e1/attendees/old${i}`);
  const { db, documents, state } = fakeFirestore(paths);
  state.onCommit = (stored) => {
    state.onCommit = null;
    stored.add("events/e1");
    stored.add("events/e1/attendees/new");
  };

  await deleteRegistrationsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

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

test("deleting a registration that is already gone changes nothing", async () => {
  const { db, state } = fakeFirestore(["events/e1"], { "events/e1": 4 });

  await deleteCountedRegistration(db, {
    path: "events/e1/attendees/u1",
    parent: { parent: { path: "events/e1" } },
  });

  assert.equal(state.counts.get("events/e1"), 4);
  assert.equal(state.commits, 0);
});
