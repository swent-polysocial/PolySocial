// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged).
// PROTOTYPE for #365, never merged: what the real, unchanged deleteAccountHandler does to an
// unsent message and to the delete-for-me markers, and the marker cleanup the design adds to
// deleteAccount, against the Firestore emulator (6 tests). From the repository root
// (firebase/firestore/PROTOTYPE-365.md):
//   firebase emulators:exec --project demo-polysocial --only firestore "node --test --test-concurrency=1 firebase/functions/chatsDeleting.prototype.emulator.test.js"
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

async function markers(uid, collection = "hiddenChats") {
  const snapshot = await db.collection(`users/${uid}/${collection}`).get();
  return snapshot.docs.map((d) => d.id).sort();
}

async function seedMarkers(uid, ids, collection = "hiddenChats") {
  for (const id of ids) {
    await db.doc(`users/${uid}/${collection}/${id}`).set({ hiddenUpTo: AT });
  }
}

/**
 * The marker cleanup the design adds to deleteAccount's chat step: delete the UID's
 * users/{uid}/hiddenChats and users/{uid}/hiddenGroupChats documents in batches until none is
 * left. [failAt] "after-first-batch" throws like a backend failure once some are deleted.
 */
async function deleteHiddenChats(uid, { failAt = null, batchSize = 500 } = {}) {
  for (const collection of ["hiddenChats", "hiddenGroupChats"]) {
    const query = db.collection(`users/${uid}/${collection}`).limit(batchSize);
    for (;;) {
      const snapshot = await query.get();
      if (snapshot.empty) break;
      const batch = db.batch();
      for (const marker of snapshot.docs) batch.delete(marker.ref);
      await batch.commit();
      if (failAt === "after-first-batch") throw new Error("injected failure");
    }
  }
}

test("the unchanged deleteAccount (#241) anonymises an unsent message and keeps it empty", async () => {
  await db.doc("users/u1").set({ uid: "u1", accountType: "student" });
  await db.doc("chats/u1~u2").set({ uids: ["u1", "u2"] });
  await db.doc("chats/u1~u2/messages/unsent").set({ senderId: "u1", text: "", createdAt: AT });
  await db.doc("chats/u1~u2/messages/kept").set({ senderId: "u1", text: "Kept", createdAt: AT });

  assert.deepEqual(await runDeleteAccount("u1"), { deleted: true });

  const unsent = await db.doc("chats/u1~u2/messages/unsent").get();
  assert.deepEqual(Object.keys(unsent.data()).sort(), ["createdAt", "senderId", "text"]);
  assert.equal(unsent.get("senderId"), "");
  assert.equal(unsent.get("text"), "");
  assert.ok(unsent.get("createdAt").isEqual(AT));
  assert.equal((await db.doc("chats/u1~u2/messages/kept").get()).get("senderId"), "");
});

test("the unchanged deleteAccount deletes users/{uid} but leaves its hiddenChats markers", async () => {
  await db.doc("users/u1").set({ uid: "u1", accountType: "student" });
  await seedMarkers("u1", ["u1~u2", "u1~u3"]);

  assert.deepEqual(await runDeleteAccount("u1"), { deleted: true });

  assert.equal((await db.doc("users/u1").get()).exists, false);
  // Deleting a document doesn't delete its subcollections: the step below is needed.
  assert.deepEqual(await markers("u1"), ["u1~u2", "u1~u3"]);
});

test("the added cleanup deletes every marker of the UID, only its own, and is idempotent", async () => {
  await seedMarkers("u1", ["u1~u2", "u1~u3", "u1~u4"]);
  await seedMarkers("u2", ["u1~u2"]);
  await deleteHiddenChats("u1", { batchSize: 2 });
  assert.deepEqual(await markers("u1"), []);
  assert.deepEqual(await markers("u2"), ["u1~u2"]);
  await deleteHiddenChats("u1");
  assert.deepEqual(await markers("u1"), []);
});

test("a failure after the first batch leaves the rest for Retry, which finishes", async () => {
  await seedMarkers("u1", ["u1~u2", "u1~u3", "u1~u4"]);
  await assert.rejects(
    deleteHiddenChats("u1", { failAt: "after-first-batch", batchSize: 2 }),
    /injected failure/,
  );
  assert.equal((await markers("u1")).length, 1);
  await deleteHiddenChats("u1", { batchSize: 2 });
  assert.deepEqual(await markers("u1"), []);
});

test("the unchanged deleteAccount anonymises an unsent group message and leaves its group markers", async () => {
  await db.doc("users/u1").set({ uid: "u1", accountType: "student" });
  await db.doc("events/e1/groups/g1").set({ memberUids: ["u1", "u2"], capacity: 4, createdBy: "u1", createdAt: AT });
  await db.doc("events/e1/groupMembers/u1").set({ groupId: "g1" });
  await db.doc("events/e1/groups/g1/messages/unsent").set({ senderId: "u1", text: "", createdAt: AT });
  await seedMarkers("u1", ["e1~g1"], "hiddenGroupChats");

  assert.deepEqual(await runDeleteAccount("u1"), { deleted: true });

  const unsent = await db.doc("events/e1/groups/g1/messages/unsent").get();
  assert.equal(unsent.get("senderId"), "");
  assert.equal(unsent.get("text"), "");
  assert.deepEqual((await db.doc("events/e1/groups/g1").get()).get("memberUids"), ["u2"]);
  assert.deepEqual(await markers("u1", "hiddenGroupChats"), ["e1~g1"]);
});

test("the added cleanup deletes the group markers too, and only the UID's", async () => {
  await seedMarkers("u1", ["u1~u2"]);
  await seedMarkers("u1", ["e1~g1", "e2~g2", "e3~g3"], "hiddenGroupChats");
  await seedMarkers("u2", ["e1~g1"], "hiddenGroupChats");
  await deleteHiddenChats("u1", { batchSize: 2 });
  assert.deepEqual(await markers("u1"), []);
  assert.deepEqual(await markers("u1", "hiddenGroupChats"), []);
  assert.deepEqual(await markers("u2", "hiddenGroupChats"), ["e1~g1"]);
});
