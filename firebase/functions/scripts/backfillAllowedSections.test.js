// Contributors: Claude (Sonnet 5.5, Anthropic) (the allowedSections backfill's unit tests, from the
// attendee recount's, #369).
"use strict";

// Unit tests with fakes, no emulator:
// node --test firebase/functions/scripts/backfillAllowedSections.test.js
// The emulator test (backfillAllowedSections.emulator.test.js) checks the real Admin SDK.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { backfillAllowedSections } = require("./backfillAllowedSections");

/**
 * A fake Firestore with events by ID, each holding its stored `allowedSections` (undefined when the
 * field is missing). It lists the events, runs transactions that read one event and update it, and
 * records every update in `updates`. `beforeTransaction` runs before each transaction, to change
 * the data in between.
 */
function fakeFirestore(events) {
  const state = { events: structuredClone(events), updates: [], beforeTransaction: null };
  const ref = (id) => ({ id });
  const db = {
    collection(name) {
      assert.equal(name, "events");
      return {
        select: (field) => {
          assert.equal(field, "allowedSections");
          return {
            async get() {
              const docs = Object.keys(state.events).map((id) => ({ ref: ref(id) }));
              return { size: docs.length, docs };
            },
          };
        },
      };
    },
    async runTransaction(update) {
      if (state.beforeTransaction) state.beforeTransaction(state.events);
      const writes = [];
      const result = await update({
        async get(target) {
          const event = state.events[target.id];
          return {
            exists: event !== undefined,
            get: (field) => (event === undefined ? undefined : event[field]),
          };
        },
        update(target, data) {
          writes.push([target.id, data]);
        },
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

test("an event without the field gets an empty list, and the others are left alone", async () => {
  const { db, state } = fakeFirestore({
    legacy: {},
    open: { allowedSections: [] },
    restricted: { allowedSections: ["IN", "SC"] },
  });

  assert.deepEqual(await backfillAllowedSections(db), { events: 3, changed: 1 });

  assert.deepEqual(state.updates, [["legacy", { allowedSections: [] }]]);
  assert.deepEqual(state.events.restricted.allowedSections, ["IN", "SC"]);
});

test("a field that is not a list is replaced, since the rules would refuse the event", async () => {
  const { db, state } = fakeFirestore({ odd: { allowedSections: "IN" }, nothing: { allowedSections: null } });

  assert.deepEqual(await backfillAllowedSections(db), { events: 2, changed: 2 });

  assert.deepEqual(state.events.odd.allowedSections, []);
  assert.deepEqual(state.events.nothing.allowedSections, []);
});

test("running it again changes nothing", async () => {
  const { db, state } = fakeFirestore({ legacy: {}, other: {} });
  await backfillAllowedSections(db);
  state.updates.length = 0;

  assert.deepEqual(await backfillAllowedSections(db), { events: 2, changed: 0 });
  assert.deepEqual(state.updates, []);
});

test("sections an organizer saved while it ran are not overwritten", async () => {
  const { db, state } = fakeFirestore({ legacy: {} });
  state.beforeTransaction = (events) => {
    events.legacy.allowedSections = ["MA"];
  };

  assert.deepEqual(await backfillAllowedSections(db), { events: 1, changed: 0 });

  assert.deepEqual(state.events.legacy.allowedSections, ["MA"]);
});

test("an event deleted meanwhile is skipped", async () => {
  const { db, state } = fakeFirestore({ gone: {}, kept: {} });
  state.beforeTransaction = (events) => {
    delete events.gone;
  };

  const result = await backfillAllowedSections(db);

  assert.deepEqual(result, { events: 2, changed: 1 });
  assert.deepEqual(state.events.kept.allowedSections, []);
});

test("no events is not an error", async () => {
  const { db } = fakeFirestore({});

  assert.deepEqual(await backfillAllowedSections(db), { events: 0, changed: 0 });
});
