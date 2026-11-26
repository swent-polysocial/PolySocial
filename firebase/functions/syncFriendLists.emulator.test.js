// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #447 under Lucas Pinto Ricardo’s supervision and approval).
"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { Timestamp } = require("firebase-admin/firestore");
const { syncFriendLists } = require("./syncFriendLists");
const { backfillFriendLists } = require("./scripts/backfillFriendLists");
const { setBackgroundTriggers, useTriggerEmulators, waitFor } = require("./testing/triggerEmulator");

const firestore = useTriggerEmulators("profile-friend-lists");
const pairId = "u1~u2";
const acceptedAt = Timestamp.fromMillis(1_790_000_001_000);
const reacceptedAt = Timestamp.fromMillis(1_790_000_002_000);

function friendship() {
  return firestore().doc(`friendships/${pairId}`);
}

function entries() {
  return firestore().getAll(
    firestore().doc("publicProfiles/u1/friends/u2"),
    firestore().doc("publicProfiles/u2/friends/u1"),
  );
}

async function listedTimes() {
  return (await entries()).map((entry) => entry.get("acceptedAt")?.toMillis() ?? null);
}

test("the write trigger reconciles both rows and a late acceptance delivery cannot restore them", async () => {
  await friendship().set({ status: "accepted", acceptedAt });
  await waitFor(listedTimes, [acceptedAt.toMillis(), acceptedAt.toMillis()]);
  for (const entry of await entries()) assert.deepEqual(entry.data(), { acceptedAt });
  const firstWrites = (await entries()).map((entry) => entry.updateTime);
  await syncFriendLists(firestore(), pairId); // Duplicate acceptance delivery.
  assert.deepEqual((await entries()).map((entry) => entry.updateTime), firstWrites);

  await friendship().delete();
  await waitFor(listedTimes, [null, null]);
  await syncFriendLists(firestore(), pairId); // Old acceptance delivered after removal.
  assert.deepEqual((await entries()).map((entry) => entry.exists), [false, false]);

  await friendship().set({ status: "pending" });
  await syncFriendLists(firestore(), pairId);
  assert.deepEqual((await entries()).map((entry) => entry.exists), [false, false]);

  await friendship().set({ status: "accepted", acceptedAt: reacceptedAt });
  await waitFor(listedTimes, [reacceptedAt.toMillis(), reacceptedAt.toMillis()]);
  await syncFriendLists(firestore(), pairId); // An older removal now sees the new acceptance.
  assert.deepEqual(await listedTimes(), [reacceptedAt.toMillis(), reacceptedAt.toMillis()]);
});

test("a pending friendship clears both stale entries and invalid pair IDs are ignored", async () => {
  const db = firestore();
  await db.doc("publicProfiles/u1/friends/u2").set({ acceptedAt });
  await db.doc("publicProfiles/u2/friends/u1").set({ acceptedAt });
  for (const id of ["u1", "u1~u2~u3", "u2~u1", "~u2"]) await syncFriendLists(db, id);
  assert.deepEqual(await listedTimes(), [acceptedAt.toMillis(), acceptedAt.toMillis()]);

  await friendship().set({ status: "pending" });
  await waitFor(listedTimes, [null, null]);
  assert.deepEqual((await entries()).map((entry) => entry.exists), [false, false]);
});

test("the backfill repairs an accepted friendship from before deployment and skips pending ones", async () => {
  const db = firestore();
  await setBackgroundTriggers(false);
  try {
    await friendship().set({ status: "accepted", acceptedAt });
    await db.doc("friendships/u1~u3").set({ status: "pending" });
    await db.doc("publicProfiles/u1/friends/u2").set({ acceptedAt: reacceptedAt });
    assert.equal(await backfillFriendLists(db), 1);
    assert.deepEqual(await listedTimes(), [acceptedAt.toMillis(), acceptedAt.toMillis()]);
    const firstWrites = (await entries()).map((entry) => entry.updateTime);
    assert.equal(await backfillFriendLists(db), 1);
    assert.deepEqual((await entries()).map((entry) => entry.updateTime), firstWrites);
    assert.equal((await db.doc("publicProfiles/u1/friends/u3").get()).exists, false);
    assert.equal((await db.doc("publicProfiles/u3/friends/u1").get()).exists, false);
  } finally {
    await setBackgroundTriggers(true);
  }
});
