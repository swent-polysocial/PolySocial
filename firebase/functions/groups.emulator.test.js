// Contributors: Claude (Opus 5.5, Anthropic) drafted the emulator test of the triggers deleting a
// cancelled event's groups and a deleted group's messages, #382.
// Contributors: Claude (Opus 5.5, Anthropic) (an account deletion emptying a group, with the
// triggers on, as a tester, #382).
"use strict";

// Deletes events and groups in the Firestore emulator and waits for the deleteEventGroups and
// deleteGroupMessages triggers, running in the Functions emulator. Run from the repository root
// (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node --test --test-concurrency=1 firebase/functions/*.emulator.test.js"
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteAccountHandler } = require("./deleteAccountHandler");
const { pathsIn, useTriggerEmulators, waitFor, waitUntil } = require("./testing/triggerEmulator");

const database = useTriggerEmulators("deleteEventGroups");

/**
 * Stores, in one batch as the demo seed script does, a fictional event, a registration from each
 * member, and for each group of `groups` (`{ groupId: { members, messages } }`) the group, its
 * members' markers and `messages` messages from its first member (`m0`, `m1`…, or `idPrefix`).
 */
async function seedEvent(eventId, groups, idPrefix = "m") {
  const db = database();
  const event = `events/${eventId}`;
  const writes = [];
  writes.push([event, { title: "Fictional study session", createdBy: "organizer" }]);
  for (const [groupId, { members, messages = 0 }] of Object.entries(groups)) {
    const group = `${event}/groups/${groupId}`;
    writes.push([group, { capacity: 4, memberUids: members, createdBy: members[0] }]);
    for (const uid of members) {
      writes.push([`${event}/groupMembers/${uid}`, { groupId }]);
      writes.push([`${event}/attendees/${uid}`, { uid, registeredAt: new Date() }]);
    }
    for (let i = 0; i < messages; i++) {
      writes.push([
        `${group}/messages/${idPrefix}${i}`,
        { senderId: members[0], text: "Fictional plan", createdAt: new Date() },
      ]);
    }
  }
  // A batch holds at most 500 writes.
  for (let start = 0; start < writes.length; start += 500) {
    const batch = db.batch();
    for (const [path, data] of writes.slice(start, start + 500)) batch.set(db.doc(path), data);
    await batch.commit();
  }
}

/** The sorted paths of every event, registration, group, marker and message left under `prefix`. */
async function stored(prefix = "") {
  const db = database();
  const events = (await db.collection("events").listDocuments()).map((ref) => ref.path);
  const nested = await Promise.all(
    ["attendees", "groups", "groupMembers", "messages"].map((id) => pathsIn(db, id)),
  );
  return [...events, ...nested.flat()].filter((path) => path.startsWith(prefix)).sort();
}

test("cancelling an event deletes its groups, markers and messages, other events' are kept", async () => {
  await seedEvent("cancelled", {
    g1: { members: ["u1", "u2"], messages: 2 },
    g2: { members: ["u3"], messages: 1 },
  });
  await seedEvent("kept", { g1: { members: ["u1"], messages: 1 } });
  const kept = await stored("events/kept/");

  await database().doc("events/cancelled").delete();

  // listDocuments also lists a missing event that still has documents under it.
  await waitFor(() => stored("events/cancelled"), []);
  assert.deepEqual(await stored("events/kept/"), kept);
});

test("a group deleted on its own loses its messages, the event's other groups keep theirs", async () => {
  await seedEvent("e", {
    solo: { members: ["u1"], messages: 2 },
    other: { members: ["u2"], messages: 1 },
  });
  const db = database();

  // The last member's leave (#232) deletes the group and their marker.
  const leave = db.batch();
  leave.delete(db.doc("events/e/groups/solo"));
  leave.delete(db.doc("events/e/groupMembers/u1"));
  await leave.commit();

  await waitFor(() => stored("events/e/groups/solo"), []);
  assert.deepEqual(await stored("events/e"), [
    "events/e",
    "events/e/attendees/u1",
    "events/e/attendees/u2",
    "events/e/groupMembers/u2",
    "events/e/groups/other",
    "events/e/groups/other/messages/m0",
  ]);
});

// deleteAccount's own emulator tests run with the triggers off; here they run together, as
// deployed. The handler is called directly, with the emulator's Admin Firestore and no Auth user.
test("an account deletion emptying a group deletes its chat, a shared one stays anonymised", async () => {
  await seedEvent("e", {
    solo: { members: ["leaving"], messages: 2 },
    shared: { members: ["leaving", "staying"], messages: 1 },
  });
  const db = database();

  const result = await deleteAccountHandler({
    auth: { uid: "leaving", token: { auth_time: 1_000_000 } },
    db,
    adminAuth: { deleteUser: async () => {} },
    now: () => 1_000_000_000,
    logger: { error() {}, warn() {} },
  });

  assert.deepEqual(result, { deleted: true });
  await waitFor(() => stored("events/e/groups/solo"), []);
  assert.deepEqual(await stored("events/e"), [
    "events/e",
    "events/e/attendees/staying",
    "events/e/groupMembers/staying",
    "events/e/groups/shared",
    "events/e/groups/shared/messages/m0",
  ]);
  const kept = await db.doc("events/e/groups/shared/messages/m0").get();
  assert.equal(kept.get("senderId"), "");
  assert.equal(kept.get("text"), "Fictional plan");
});

test("a group with more messages than one batch holds loses them all", async () => {
  await seedEvent("busy", { g1: { members: ["u1", "u2"], messages: 501 } });
  await database().doc("events/busy/groups/g1").delete();
  await waitFor(() => stored("events/busy/groups/"), []);
});

// The demo seed script deletes its events and writes them back under the same IDs, with the same
// group IDs, in one batch.
test("an event written again under its ID keeps its new groups, markers and messages", async () => {
  await seedEvent("again", { g1: { members: ["u1"], messages: 1 } });
  await seedEvent("gone", { g1: { members: ["u1"], messages: 1 } });

  await database().doc("events/again").delete();
  await seedEvent("again", { g1: { members: ["u2"], messages: 1 } }, "new");
  await database().doc("events/gone").delete();

  // Wait for the later deletion's triggers, then give the earlier ones a margin to run too. The
  // old marker and message may be gone or kept, depending on whether those triggers ran before
  // the event came back; the new ones must stay.
  await waitUntil(() => stored("events/gone"), (left) => left.length === 0);
  await new Promise((resolve) => setTimeout(resolve, 1000));
  const left = await stored("events/again/");
  for (const path of ["groups/g1", "groupMembers/u2", "groups/g1/messages/new0"]) {
    assert.ok(left.includes(`events/again/${path}`), `events/again/${path} was deleted`);
  }
});

test("a group written again under its ID keeps its new messages", async () => {
  await seedEvent("e", {
    g1: { members: ["u1"], messages: 1 },
    gone: { members: ["u2"], messages: 1 },
  });
  const db = database();

  await db.doc("events/e/groups/g1").delete();
  const again = db.batch();
  again.set(db.doc("events/e/groups/g1"), { capacity: 4, memberUids: ["u3"], createdBy: "u3" });
  again.set(db.doc("events/e/groups/g1/messages/new"), { senderId: "u3", text: "Fictional plan" });
  await again.commit();
  await db.doc("events/e/groups/gone").delete();

  await waitFor(() => stored("events/e/groups/gone"), []);
  await new Promise((resolve) => setTimeout(resolve, 1000));
  assert.ok((await stored("events/e/groups/g1/")).includes("events/e/groups/g1/messages/new"));
});
