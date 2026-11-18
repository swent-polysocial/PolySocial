// Contributors: Claude (Opus 5.5, Anthropic) (the attendee recount's unit tests, #405; inherited
// option names refused, after review).
"use strict";

// Unit tests with fakes, no emulator: node --test firebase/functions/scripts/recountAttendees.test.js
// The emulator test (recountAttendees.emulator.test.js) checks the real Admin SDK, including a
// counter stored as 3.0.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { parseProjectArgs } = require("./projectArgs");
const { recountAttendees } = require("./recountAttendees");

/**
 * A fake Firestore with events (by ID, with their stored `attendeeCount`, or none) and their
 * registrations. It lists the events, runs transactions that read an event and count its
 * registrations, and records each update in `updates`. `beforeTransaction` runs before each
 * transaction, to change the data in between.
 */
function fakeFirestore(events) {
  const state = { events: structuredClone(events), updates: [], beforeTransaction: null };
  const ref = (id) => ({
    id,
    collection: (name) => {
      assert.equal(name, "attendees");
      return { count: () => ({ countOf: id }) };
    },
  });
  const db = {
    collection(name) {
      assert.equal(name, "events");
      return {
        select: () => ({
          async get() {
            const docs = Object.keys(state.events).map((id) => ({ ref: ref(id) }));
            return { size: docs.length, docs };
          },
        }),
      };
    },
    async runTransaction(update) {
      if (state.beforeTransaction) state.beforeTransaction(state.events);
      const writes = [];
      const result = await update({
        async get(target) {
          if (target.countOf !== undefined) {
            const count = state.events[target.countOf]?.attendees.length ?? 0;
            return { data: () => ({ count }) };
          }
          const event = state.events[target.id];
          return { exists: event !== undefined, get: (field) => event?.[field] };
        },
        update: (target, data) => writes.push([target.id, data]),
      });
      for (const [id, data] of writes) {
        state.updates.push([id, data]);
        Object.assign(state.events[id], data);
      }
      return result;
    },
  };
  return { db, state };
}

test("every event gets the number of its registrations, and the changed ones are counted", async () => {
  const { db, state } = fakeFirestore({
    legacy: { attendees: ["u1", "u2", "u3"] },
    empty: { attendees: [] },
    drifted: { attendeeCount: 5, attendees: ["u1"] },
    right: { attendeeCount: 2, attendees: ["u1", "u2"] },
  });

  assert.deepEqual(await recountAttendees(db), { events: 4, changed: 3 });

  const counts = Object.fromEntries(
    Object.entries(state.events).map(([id, event]) => [id, event.attendeeCount]),
  );
  assert.deepEqual(counts, { legacy: 3, empty: 0, drifted: 1, right: 2 });
});

test("it writes every count, even an equal one, and only attendeeCount", async () => {
  const { db, state } = fakeFirestore({ right: { attendeeCount: 2, attendees: ["u1", "u2"] } });

  await recountAttendees(db);

  // Node reads a counter stored as 2.0 as 2: only a write stores it as a whole number again.
  assert.deepEqual(state.updates, [["right", { attendeeCount: 2 }]]);
});

test("running it again writes the same counts and reports no change", async () => {
  const { db, state } = fakeFirestore({
    legacy: { attendees: ["u1"] },
    drifted: { attendeeCount: 7, attendees: [] },
  });

  await recountAttendees(db);
  const first = structuredClone(state.events);

  assert.deepEqual(await recountAttendees(db), { events: 2, changed: 0 });
  assert.deepEqual(state.events, first);
});

test("an event deleted after the listing is skipped, not written again", async () => {
  const { db, state } = fakeFirestore({
    gone: { attendeeCount: 1, attendees: ["u1"] },
    kept: { attendees: ["u1"] },
  });
  state.beforeTransaction = (events) => {
    state.beforeTransaction = null;
    delete events.gone;
  };

  assert.deepEqual(await recountAttendees(db), { events: 2, changed: 1 });
  assert.deepEqual(state.updates, [["kept", { attendeeCount: 1 }]]);
});

test("no events, nothing to write", async () => {
  const { db, state } = fakeFirestore({});

  assert.deepEqual(await recountAttendees(db), { events: 0, changed: 0 });
  assert.deepEqual(state.updates, []);
});

const REAL = {};
const EMULATOR = { FIRESTORE_EMULATOR_HOST: "127.0.0.1:8080" };

test("the recount needs the project, and --yes on a real project", () => {
  assert.deepEqual(parseProjectArgs(["--project", "polysocial-adba0", "--yes"], REAL), {
    project: "polysocial-adba0",
    flags: {},
  });
  assert.throws(() => parseProjectArgs(["--project", "polysocial-adba0"], REAL), /--yes/);
  assert.throws(() => parseProjectArgs(["--yes"], REAL), /--project/);
});

test("the recount has no other option, and keeps demo- projects to the emulator", () => {
  assert.throws(
    () => parseProjectArgs(["--project", "demo-polysocial", "--remove"], EMULATOR),
    /Unknown option --remove/,
  );
  assert.throws(() => parseProjectArgs(["--project", "polysocial-adba0"], EMULATOR), /demo-/);
  assert.throws(() => parseProjectArgs(["--project", "demo-polysocial", "--yes"], REAL), /emulator/);
  assert.deepEqual(parseProjectArgs(["--project", "demo-polysocial"], EMULATOR), {
    project: "demo-polysocial",
    flags: {},
  });
});

test("an option named like an object's built-in property is refused, not taken for a flag", () => {
  for (const option of ["--toString", "--constructor", "--__proto__", "--hasOwnProperty"]) {
    assert.throws(
      () => parseProjectArgs(["--project", "demo-polysocial", option], EMULATOR),
      new RegExp(`Unknown option ${option}`),
    );
  }
});
