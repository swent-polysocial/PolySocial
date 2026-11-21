// Contributors: Claude (Opus 5.5, Anthropic) drafted the tests of deleting a cancelled event's
// groups and a deleted group's messages, #382.
// Contributors: Claude (Opus 5.5, Anthropic) (the deletion triggers' deploy manifest, as a tester,
// #382).
"use strict";

// Unit tests with fakes, no emulator: node firebase/functions/groups.test.js
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteGroupsOfDeletedEvent, deleteMessagesOfDeletedGroup } = require("./groups");
const { MAX_BATCH_WRITES, TRIGGER_REGION } = require("./registrations");
const { backendError, fakeFirestore, fakeLogger } = require("./testing/fakeFirestore");

/** The group whose deletion most tests run deleteGroupMessages for. */
const G1 = { eventId: "e1", groupId: "g1" };

/** `count` paths `${prefix}0`, `${prefix}1`… */
const numbered = (prefix, count) => Array.from({ length: count }, (_, i) => `${prefix}${i}`);

test("a cancelled event's groups and markers are deleted, its registrations and other events' are kept", async () => {
  const { db, documents } = fakeFirestore([
    "events/e1/groups/g1",
    "events/e1/groups/g2",
    "events/e1/groupMembers/u1",
    "events/e1/groupMembers/u2",
    "events/e1/groupMembers/u3",
    "events/e1/attendees/u1",
    "events/e2",
    "events/e2/groups/g3",
    "events/e2/groupMembers/u1",
  ]);
  const { logger, logs } = fakeLogger();

  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger });

  // The registrations are deleteEventRegistrations' job.
  assert.deepEqual([...documents], [
    "events/e1/attendees/u1",
    "events/e2",
    "events/e2/groups/g3",
    "events/e2/groupMembers/u1",
  ]);
  assert.deepEqual(logs, []);
});

test("an event without groups deletes nothing and succeeds", async () => {
  const { db, documents, state } = fakeFirestore(["events/e2/groups/g1"]);
  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });
  assert.equal(state.commits, 0);
  assert.equal(documents.size, 1);
});

test("more groups and markers than one batch holds are deleted in several batches", async () => {
  const { db, documents, state } = fakeFirestore([
    ...numbered("events/e1/groups/g", MAX_BATCH_WRITES + 1),
    ...numbered("events/e1/groupMembers/u", MAX_BATCH_WRITES + 1),
  ]);

  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  assert.equal(documents.size, 0);
  assert.equal(state.commits, 4);
});

test("a group started while deleting is deleted too", async () => {
  const { db, documents, state } = fakeFirestore(["events/e1/groups/g1"]);
  state.onCommit = (stored) => {
    state.onCommit = null;
    stored.add("events/e1/groups/g2");
    stored.add("events/e1/groupMembers/u2");
  };

  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  assert.equal(documents.size, 0);
});

test("a failure midway is thrown again, logs only its code, and the retry finishes", async () => {
  const { db, documents, state } = fakeFirestore([
    "events/e1/groups/g1",
    "events/e1/groupMembers/u1",
  ]);
  const { logger, logs } = fakeLogger();
  // The groups' batch commits, then the next commit fails, before the markers are deleted.
  state.onCommit = () => {
    state.onCommit = null;
    state.failCommit = backendError(14);
  };

  await assert.rejects(deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger }), { code: 14 });
  assert.deepEqual([...documents], ["events/e1/groupMembers/u1"]);
  assert.deepEqual(logs, [["error", "deleteEventGroups failed", { code: 14 }]]);
  const logged = JSON.stringify(logs);
  assert.ok(!logged.includes("e1") && !logged.includes("u1"), "no event or uid is logged");

  // The trigger's retry finishes the job.
  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger });
  assert.equal(documents.size, 0);
});

// The demo seed script deletes its events and writes them back under the same IDs, with their
// groups and markers, before this trigger may run.
test("an event written again before the trigger runs keeps its groups and markers", async () => {
  const paths = ["events/e1", "events/e1/groups/g1", "events/e1/groupMembers/u1"];
  const { db, documents, state } = fakeFirestore(paths);

  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  assert.deepEqual([...documents], paths);
  assert.equal(state.commits, 0);
});

test("an event written again while deleting keeps the groups and markers written with it", async () => {
  const { db, documents, state } = fakeFirestore([
    ...numbered("events/e1/groups/old", MAX_BATCH_WRITES + 1),
    "events/e1/groupMembers/old",
  ]);
  state.onCommit = (stored) => {
    state.onCommit = null;
    stored.add("events/e1");
    stored.add("events/e1/groups/new");
    stored.add("events/e1/groupMembers/new");
  };

  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger: fakeLogger().logger });

  // The first batch went before the event came back; the rest now belongs to the new event.
  assert.equal(state.commits, 1);
  assert.ok(documents.has("events/e1/groups/new"));
  assert.ok(documents.has("events/e1/groupMembers/new"));
  assert.ok(documents.has(`events/e1/groups/old${MAX_BATCH_WRITES}`));
});

test("a group deleted on its own loses its messages, the event's other groups keep theirs", async () => {
  // Its last member left (#232): the event and its other group are still there.
  const { db, documents } = fakeFirestore([
    "events/e1",
    "events/e1/groups/g1/messages/m1",
    "events/e1/groups/g1/messages/m2",
    "events/e1/groups/g2",
    "events/e1/groups/g2/messages/m1",
    "events/e1/groupMembers/u2",
    "events/e2/groups/g1/messages/m1",
  ]);
  const { logger, logs } = fakeLogger();

  await deleteMessagesOfDeletedGroup({ ...G1, db, logger });

  assert.deepEqual([...documents], [
    "events/e1",
    "events/e1/groups/g2",
    "events/e1/groups/g2/messages/m1",
    "events/e1/groupMembers/u2",
    "events/e2/groups/g1/messages/m1",
  ]);
  assert.deepEqual(logs, []);
});

test("a cancelled event loses its groups, markers and messages through both triggers", async () => {
  const { db, documents } = fakeFirestore([
    "events/e1/groups/g1",
    "events/e1/groups/g1/messages/m1",
    "events/e1/groups/g1/messages/m2",
    "events/e1/groups/g2",
    "events/e1/groups/g2/messages/m1",
    "events/e1/groupMembers/u1",
    "events/e1/groupMembers/u2",
    "events/e2",
    "events/e2/groups/g1",
    "events/e2/groups/g1/messages/m1",
  ]);
  const { logger } = fakeLogger();
  const groupsBefore = [...documents].filter((path) => /^events\/e1\/groups\/[^/]+$/.test(path));

  await deleteGroupsOfDeletedEvent({ eventId: "e1", db, logger });
  // Firestore then runs deleteGroupMessages once for each group the first trigger deleted.
  for (const path of groupsBefore.filter((group) => !documents.has(group))) {
    const [, eventId, , groupId] = path.split("/");
    await deleteMessagesOfDeletedGroup({ eventId, groupId, db, logger });
  }

  assert.deepEqual([...documents], [
    "events/e2",
    "events/e2/groups/g1",
    "events/e2/groups/g1/messages/m1",
  ]);
});

test("more messages than one batch holds are deleted in several batches", async () => {
  const { db, documents, state } = fakeFirestore(
    numbered("events/e1/groups/g1/messages/m", MAX_BATCH_WRITES * 2 + 1),
  );

  await deleteMessagesOfDeletedGroup({ ...G1, db, logger: fakeLogger().logger });

  assert.equal(documents.size, 0);
  assert.equal(state.commits, 3);
});

test("a failure deleting messages is thrown again, logs only its code, and the retry finishes", async () => {
  const { db, documents, state } = fakeFirestore(
    numbered("events/e1/groups/g1/messages/m", MAX_BATCH_WRITES + 1),
  );
  const { logger, logs } = fakeLogger();
  // The first batch commits, the second fails.
  state.onCommit = () => {
    state.onCommit = null;
    state.failCommit = backendError(10);
  };

  await assert.rejects(deleteMessagesOfDeletedGroup({ ...G1, db, logger }), { code: 10 });
  assert.equal(documents.size, 1);
  assert.deepEqual(logs, [["error", "deleteGroupMessages failed", { code: 10 }]]);
  const logged = JSON.stringify(logs);
  assert.ok(!logged.includes("e1") && !logged.includes("g1"), "no event or group is logged");

  // The trigger's retry finishes the job.
  await deleteMessagesOfDeletedGroup({ ...G1, db, logger });
  assert.equal(documents.size, 0);
});

// The demo seed script deletes its groups with their events and writes them back under the same
// IDs before this trigger may run.
test("a group written again before the trigger runs keeps its messages", async () => {
  const paths = ["events/e1/groups/g1", "events/e1/groups/g1/messages/m1"];
  const { db, documents, state } = fakeFirestore(paths);

  await deleteMessagesOfDeletedGroup({ ...G1, db, logger: fakeLogger().logger });

  assert.deepEqual([...documents], paths);
  assert.equal(state.commits, 0);
});

test("a group written again while deleting keeps the messages written with it", async () => {
  const { db, documents, state } = fakeFirestore(
    numbered("events/e1/groups/g1/messages/old", MAX_BATCH_WRITES + 1),
  );
  state.onCommit = (stored) => {
    state.onCommit = null;
    stored.add("events/e1/groups/g1");
    stored.add("events/e1/groups/g1/messages/new");
  };

  await deleteMessagesOfDeletedGroup({ ...G1, db, logger: fakeLogger().logger });

  // The first batch went before the group came back; the rest now belongs to the new group.
  assert.equal(state.commits, 1);
  assert.ok(documents.has("events/e1/groups/g1/messages/new"));
  assert.equal(documents.size, 3);
});

// The manifest the Firebase CLI deploys from, for the three triggers index.js builds with
// onDeleted: without retries a failed cleanup never runs again, Eventarc delivers eur3's events
// only to TRIGGER_REGION, and a trigger on another document never runs for this deletion. The
// Functions emulator never retries and runs a Firestore trigger in any region, so no emulator test
// sees these.
test("the deletion triggers run on their deletion, in TRIGGER_REGION, with retries", () => {
  const functions = require("./index");

  for (const [name, document] of [
    ["deleteEventRegistrations", "events/{eventId}"],
    ["deleteEventGroups", "events/{eventId}"],
    ["deleteGroupMessages", "events/{eventId}/groups/{groupId}"],
  ]) {
    const endpoint = functions[name].__endpoint;
    assert.deepEqual(endpoint.region, [TRIGGER_REGION], name);
    const { eventType, eventFilterPathPatterns, retry } = endpoint.eventTrigger;
    assert.equal(eventType, "google.cloud.firestore.document.v1.deleted", name);
    assert.deepEqual(eventFilterPathPatterns, { document }, name);
    assert.equal(retry, true, name);
  }
});
