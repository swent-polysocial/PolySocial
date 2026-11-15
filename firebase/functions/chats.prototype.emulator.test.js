// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged).
// PROTOTYPE for #365, never merged: what the real, unchanged deleteAccountHandler does to
// chats/{pairId}/messages, and the chat step the design adds, against the Firestore emulator
// (8 tests). From the repository root (firebase/firestore/PROTOTYPE-365.md):
//   firebase emulators:exec --project demo-polysocial --only firestore "node --test --test-concurrency=1 firebase/functions/chats.prototype.emulator.test.js"
"use strict";
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { deleteAccountHandler } = require("./deleteAccountHandler");

const PROJECT_ID = "demo-polysocial";
if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error("Run inside firebase emulators:exec");

let app;
let db;
before(() => {
  app = initializeApp({ projectId: PROJECT_ID });
  db = getFirestore(app);
});
after(() => deleteApp(app));
beforeEach(async () => {
  const url = `http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`;
  const response = await fetch(url, { method: "DELETE" });
  if (!response.ok) throw new Error(`Could not clear the emulator (${response.status}).`);
});

const AT = Timestamp.fromMillis(1_790_000_000_000);
const logger = { error() {}, warn() {} };
const adminAuth = { deleteUser: async () => {} };

function runDeleteAccount(uid) {
  return deleteAccountHandler({
    auth: { uid, token: { auth_time: Math.floor(Date.now() / 1000) } },
    db,
    adminAuth,
    now: Date.now,
    logger,
  });
}

async function seedChat(pairId, uids, messages) {
  await db.doc(`chats/${pairId}`).set({ uids });
  for (const [id, senderId] of Object.entries(messages)) {
    await db.doc(`chats/${pairId}/messages/${id}`).set({ senderId, text: `Text of ${id}`, createdAt: AT });
  }
}

function markDeleted(uid) {
  return db.doc(`deletedAccounts/${uid}`).set({});
}

async function senders(pairId) {
  const snapshot = await db.collection(`chats/${pairId}/messages`).get();
  return Object.fromEntries(snapshot.docs.map((d) => [d.id, d.get("senderId")]));
}

async function uidsOf(pairId) {
  const chat = await db.doc(`chats/${pairId}`).get();
  return chat.exists ? chat.get("uids") : null;
}

/** The IDs the step's own query finds for [uid]: what a Retry would work on. */
async function chatsStillListing(uid) {
  const chats = await db.collection("chats").where("uids", "array-contains", uid).get();
  return chats.docs.map((d) => d.id).sort();
}

test("the unchanged deleteAccount (#241) already anonymises the account's one-to-one messages", async () => {
  await db.doc("users/u1").set({ uid: "u1", accountType: "student" });
  await seedChat("u1~u2", ["u1", "u2"], { a: "u1", b: "u2", c: "u1" });
  await seedChat("u1~u3", ["u1", "u3"], { d: "u1" });
  await seedChat("u2~u3", ["u2", "u3"], { e: "u2", f: "u3" });
  // A group message of the same account, to show one query serves both kinds of chat.
  await db.doc("events/e1/groups/g1/messages/g").set({ senderId: "u1", text: "Group", createdAt: AT });

  assert.deepEqual(await runDeleteAccount("u1"), { deleted: true });

  assert.deepEqual(await senders("u1~u2"), { a: "", b: "u2", c: "" });
  assert.deepEqual(await senders("u1~u3"), { d: "" });
  assert.deepEqual(await senders("u2~u3"), { e: "u2", f: "u3" });
  assert.equal((await db.doc("events/e1/groups/g1/messages/g").get()).get("senderId"), "");
  // Text and time are kept.
  const kept = await db.doc("chats/u1~u2/messages/a").get();
  assert.equal(kept.get("text"), "Text of a");
  assert.ok(kept.get("createdAt").isEqual(AT));
  // What it does NOT touch today: the chat document still lists the deleted UID.
  assert.deepEqual(await uidsOf("u1~u2"), ["u1", "u2"]);
  assert.equal((await db.doc("deletedAccounts/u1").get()).exists, true);
});

/**
 * The step the design adds to deleteAccount, after the friendships are deleted. For each chat
 * that lists the UID, one transaction decides:
 * - another UID is still an account (no deletedAccounts marker): write uids without the deleted
 *   UID, so the other student keeps the chat;
 * - nobody else is left (the UID is the only one, or every other UID has a deletion marker):
 *   write nothing. uids stays as it is, so this step's query still finds the chat until it is
 *   gone; then the messages are deleted, then the chat.
 * [failAt] makes it throw at that point, like a backend failure: "between-deletes" (the messages
 * are gone, the chat isn't yet) or "after-first-batch" (some messages are gone).
 */
async function leaveChats(uid, { failAt = null, batchSize = 500 } = {}) {
  const chats = await db.collection("chats").where("uids", "array-contains", uid).get();
  for (const chat of chats.docs) {
    const nobodyLeft = await db.runTransaction(async (transaction) => {
      const current = await transaction.get(chat.ref);
      if (!current.exists) return false;
      const uids = current.get("uids");
      const others = Array.isArray(uids) ? uids.filter((member) => member !== uid) : [];
      const stillAccounts = [];
      for (const other of others) {
        const marker = await transaction.get(db.doc(`deletedAccounts/${other}`));
        if (!marker.exists) stillAccounts.push(other);
      }
      if (stillAccounts.length === 0) return true;
      transaction.update(chat.ref, { uids: others });
      return false;
    });
    if (!nobodyLeft) continue;
    for (;;) {
      const messages = await chat.ref.collection("messages").limit(batchSize).get();
      if (messages.empty) break;
      const batch = db.batch();
      for (const message of messages.docs) batch.delete(message.ref);
      await batch.commit();
      if (failAt === "after-first-batch") throw new Error("injected failure");
    }
    if (failAt === "between-deletes") throw new Error("injected failure");
    await chat.ref.delete();
  }
}

test("the added step takes the UID out of uids, keeps the other student's chat, and is idempotent", async () => {
  await markDeleted("u1");
  await seedChat("u1~u2", ["u1", "u2"], { a: "", b: "u2" });
  await seedChat("u2~u3", ["u2", "u3"], { e: "u2" });
  await leaveChats("u1");
  await leaveChats("u1");
  assert.deepEqual(await uidsOf("u1~u2"), ["u2"]);
  assert.deepEqual(await senders("u1~u2"), { a: "", b: "u2" });
  assert.deepEqual(await uidsOf("u2~u3"), ["u2", "u3"]);
  // The other student still finds it with the Chats tab's query.
  assert.deepEqual(await chatsStillListing("u2"), ["u1~u2", "u2~u3"]);
});

test("when the second student deletes their account too, the chat and its messages are deleted", async () => {
  await markDeleted("u1");
  await markDeleted("u2");
  await seedChat("u1~u2", ["u2"], { a: "", b: "" });
  await seedChat("u2~u3", ["u2", "u3"], { e: "", f: "u3" });
  await leaveChats("u2");
  assert.equal(await uidsOf("u1~u2"), null);
  assert.deepEqual(await senders("u1~u2"), {});
  assert.deepEqual(await uidsOf("u2~u3"), ["u3"]);
  assert.deepEqual(await senders("u2~u3"), { e: "", f: "u3" });
  await leaveChats("u2");
});

for (const failAt of ["between-deletes", "after-first-batch"]) {
  test(`a failure ${failAt} leaves the last UID in uids, so Retry finds the chat and finishes`, async () => {
    await markDeleted("u1");
    await markDeleted("u2");
    await seedChat("u1~u2", ["u2"], { a: "", b: "", c: "" });
    await assert.rejects(leaveChats("u2", { failAt, batchSize: 2 }), /injected failure/);
    // Interrupted: the chat document is still there, with the last UID, so the query finds it.
    assert.deepEqual(await uidsOf("u1~u2"), ["u2"]);
    assert.deepEqual(await chatsStillListing("u2"), ["u1~u2"]);
    if (failAt === "between-deletes") assert.deepEqual(await senders("u1~u2"), {});
    else assert.equal(Object.keys(await senders("u1~u2")).length, 1);
    // Retry.
    await leaveChats("u2", { batchSize: 2 });
    assert.equal(await uidsOf("u1~u2"), null);
    assert.deepEqual(await senders("u1~u2"), {});
    assert.deepEqual(await chatsStillListing("u2"), []);
  });
}

test("written literally (uids emptied first), the same failure hides the chat from Retry", async () => {
  // The order the design must NOT be read as: write uids without the last UID, then delete.
  await seedChat("u1~u2", ["u2"], { a: "", b: "" });
  await db.doc("chats/u1~u2").update({ uids: [] });
  // A failure here: Retry's query no longer finds the chat, and both texts stay stored.
  assert.deepEqual(await chatsStillListing("u2"), []);
  assert.equal(Object.keys(await senders("u1~u2")).length, 2);
});

test("an earlier deletion that stopped before its chats step doesn't keep the chat alive", async () => {
  // u1's deletion wrote its marker and anonymised its messages, then stopped: u1 is still in uids.
  await markDeleted("u1");
  await seedChat("u1~u2", ["u1", "u2"], { a: "", b: "u2" });
  // A chat with a student who still has an account, for contrast.
  await seedChat("u2~u3", ["u2", "u3"], { e: "u2", f: "u3" });
  // u2 deletes their account: nobody with an account is left in u1~u2.
  await markDeleted("u2");
  await leaveChats("u2");
  assert.equal(await uidsOf("u1~u2"), null);
  assert.deepEqual(await senders("u1~u2"), {});
  assert.deepEqual(await uidsOf("u2~u3"), ["u3"]);
  // If u1's deletion is retried later, its step finds nothing left to do.
  await leaveChats("u1");
  assert.deepEqual(await chatsStillListing("u1"), []);
});

test("while the other student still has an account, an unfinished earlier step changes nothing for them", async () => {
  // u1's deletion stopped before its chats step; u2 keeps their account and their chat.
  await markDeleted("u1");
  await seedChat("u1~u2", ["u1", "u2"], { a: "", b: "u2" });
  await leaveChats("u1");
  assert.deepEqual(await uidsOf("u1~u2"), ["u2"]);
  assert.deepEqual(await senders("u1~u2"), { a: "", b: "u2" });
});
