// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount emulator test, #206; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (the public profile deleted with the account, #229; profiles can't be saved
// again once deleted, after review, #229; the user's registrations, #275; the user's
// friendships, #357).
// Contributors: Claude (Opus 5.5, Anthropic) (the attendeeCount the deletion lowers, #405).
// Contributors: Claude (Opus 5.5, Anthropic) (the deletion triggers turned off while it runs, #382).
// Contributors: Claude (Opus 5.5, Anthropic) (one-to-one chats and delete-for-me markers, #429).
// Contributors: Claude (Opus 5.5, Anthropic) (two accounts deleted together, stopping partway, #429).
"use strict";
// Contributors: OpenAI Codex (real membership cleanup and retryable message anonymisation, #241).

// Calls the deployed-to-emulator deleteAccount over HTTP, as the Android client does, against the
// Auth, Firestore and Functions emulators. Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node --test --test-concurrency=1 firebase/functions/*.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { DocumentReference, getFirestore, Timestamp } = require("firebase-admin/firestore");
const { DETAILS, REGION, deleteAccountHandler } = require("./deleteAccountHandler");
const { setBackgroundTriggers } = require("./testing/triggerEmulator");

// A "demo-" project never reaches a real Firebase project.
const PROJECT_ID = "demo-polysocial";
// emulators:exec sets these for the emulators it starts; the Functions port is in firebase.json.
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;
const AUTH_HOST = process.env.FIREBASE_AUTH_EMULATOR_HOST;
const FUNCTIONS_ORIGIN = "http://127.0.0.1:5001";
const DELETE_ACCOUNT_URL = `${FUNCTIONS_ORIGIN}/${PROJECT_ID}/${REGION}/deleteAccount`;
const PASSWORD = "fictional-password";

if (!FIRESTORE_HOST || !AUTH_HOST) {
  throw new Error(
    "FIRESTORE_EMULATOR_HOST and FIREBASE_AUTH_EMULATOR_HOST are not set: run this test inside " +
      '`firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "..."`.',
  );
}

let app;
let db;
let auth;

before(async () => {
  // These tests call deleteAccount alone. The deletion triggers (#275, #382) would otherwise run on
  // what each test deletes or clears, late enough to delete what the next one writes under the same
  // IDs (such as a group seeded without its event).
  await setBackgroundTriggers(false);
  try {
    // A running emulator can still have failed to discover the function. Probe the callable
    // without an account: its expected 401 proves it loaded, without deleting any data.
    const probe = await fetch(DELETE_ACCOUNT_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ data: {} }),
    });
    if (probe.status !== 401) {
      throw new Error(`deleteAccount is not ready (HTTP ${probe.status}); check function discovery.`);
    }
  } catch (error) {
    throw new Error(
      `deleteAccount is unavailable at ${DELETE_ACCOUNT_URL}: start Functions and check its load log.`,
      { cause: error },
    );
  }
  app = initializeApp({ projectId: PROJECT_ID });
  db = getFirestore(app);
  auth = getAuth(app);
});

after(async () => {
  await setBackgroundTriggers(true);
  if (app) await deleteApp(app);
});

beforeEach(async () => {
  await clearEmulator(
    `http://${FIRESTORE_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
  );
  await clearEmulator(`http://${AUTH_HOST}/emulator/v1/projects/${PROJECT_ID}/accounts`);
});

async function clearEmulator(url) {
  const response = await fetch(url, { method: "DELETE" });
  if (!response.ok) throw new Error(`Could not clear the emulator (${response.status}).`);
}

/**
 * Creates a fictional verified user, with a profile when `profile` is set (and a student's public
 * profile, #229), and an event.
 */
async function seedUser(email, profile) {
  const { uid } = await auth.createUser({ email, password: PASSWORD, emailVerified: true });
  if (profile) await db.doc(`users/${uid}`).set({ uid, email, ...profile });
  if (profile && profile.accountType === "student") {
    await db.doc(`publicProfiles/${uid}`).set({
      displayName: "Fictional Student",
      section: "IN",
      year: "BA3",
      interests: [],
    });
  }
  await db.doc(`events/event-of-${uid}`).set({
    title: "Fictional study session",
    createdBy: uid,
    organizerIds: [uid],
    allowedUids: [uid],
    isPrivate: false,
  });
  return uid;
}

/** Signs in with the Auth emulator's REST API (any API key works) and returns the ID token. */
async function signIn(email) {
  const response = await fetch(
    `http://${AUTH_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=any`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password: PASSWORD, returnSecureToken: true }),
    },
  );
  const body = await response.json();
  if (!response.ok) throw new Error(`Sign-in failed (${response.status}).`);
  return body.idToken;
}

/** Calls deleteAccount with the callable protocol and returns the HTTP status and JSON body. */
async function callDeleteAccount(idToken) {
  const headers = { "Content-Type": "application/json" };
  if (idToken) headers.Authorization = `Bearer ${idToken}`;
  const response = await fetch(DELETE_ACCOUNT_URL, {
    method: "POST",
    headers,
    body: JSON.stringify({ data: {} }),
  });
  return { status: response.status, body: await response.json() };
}

async function authUserExists(uid) {
  try {
    await auth.getUser(uid);
    return true;
  } catch (error) {
    if (error.code === "auth/user-not-found") return false;
    throw error;
  }
}

async function documentExists(path) {
  return (await db.doc(path).get()).exists;
}

test("a student's profiles and Auth user are deleted, their event is kept", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const idToken = await signIn("student@epfl.ch");

  const { status, body } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  assert.deepEqual(body, { result: { deleted: true } });
  assert.equal(await documentExists(`users/${uid}`), false);
  assert.equal(await documentExists(`publicProfiles/${uid}`), false);
  assert.equal(await documentExists(`deletedAccounts/${uid}`), true);
  assert.equal(await authUserExists(uid), false);
  assert.equal(await documentExists(`events/event-of-${uid}`), true);
});

/** Stores a registration `events/{eventId}/attendees/{uid}`, as the app writes it. */
async function seedRegistration(eventId, uid) {
  await db.doc(`events/${eventId}/attendees/${uid}`).set({ uid, registeredAt: new Date() });
}

test("a student's registrations are deleted, other students' are kept", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const other = await seedUser("other@epfl.ch", { accountType: "student" });
  await seedRegistration(`event-of-${other}`, uid);
  await seedRegistration(`event-of-${uid}`, uid);
  await seedRegistration(`event-of-${other}`, other);
  const idToken = await signIn("student@epfl.ch");

  const { status } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  const left = await db.collectionGroup("attendees").get();
  assert.deepEqual(
    left.docs.map((document) => document.ref.path),
    [`events/event-of-${other}/attendees/${other}`],
  );
});

test("a student's friendships are deleted, other students' are kept (#357)", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const other = await seedUser("other@epfl.ch", { accountType: "student" });
  const sorted = (a, b) => [a, b].sort();
  const pair = (a, b) => sorted(a, b).join("~");
  await db.doc(`friendships/${pair(uid, other)}`).set({
    uids: sorted(uid, other), requestedBy: other, status: "accepted",
    createdAt: new Date(), acceptedAt: new Date(),
  });
  await db.doc(`friendships/${pair(uid, "fictional-1")}`).set({
    uids: sorted(uid, "fictional-1"), requestedBy: uid, status: "pending", createdAt: new Date(),
  });
  await db.doc(`friendships/${pair(other, "fictional-2")}`).set({
    uids: sorted(other, "fictional-2"), requestedBy: other, status: "pending", createdAt: new Date(),
  });
  const idToken = await signIn("student@epfl.ch");

  const { status } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  const left = await db.collection("friendships").get();
  assert.deepEqual(left.docs.map((document) => document.id), [pair(other, "fictional-2")]);
});

test("each event the student was going to counts one student less (#405)", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const other = await seedUser("other@epfl.ch", { accountType: "student" });
  await db.doc("events/shared").set({ title: "Shared", attendeeCount: 2 });
  await seedRegistration("shared", uid);
  await seedRegistration("shared", other);
  await db.doc("events/legacy").set({ title: "Before the counter" });
  await seedRegistration("legacy", uid);
  const idToken = await signIn("student@epfl.ch");

  const { status } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  assert.equal((await db.doc("events/shared").get()).get("attendeeCount"), 1);
  assert.equal((await db.doc("events/legacy").get()).get("attendeeCount"), undefined);
  const left = await db.collectionGroup("attendees").get();
  assert.deepEqual(left.docs.map((document) => document.ref.path), [`events/shared/attendees/${other}`]);
});

/** Stores the one-to-one chat of `a` and `b` (#365) under their sorted pair ID, with `messages` by ID. */
async function seedChat(a, b, messages) {
  const uids = [a, b].sort();
  const chat = db.doc(`chats/${uids.join("~")}`);
  await chat.set({ uids });
  for (const [id, message] of Object.entries(messages)) {
    await chat.collection("messages").doc(id).set(message);
  }
  return chat;
}

/** The IDs of the documents in the collection `path`, sorted. */
async function idsIn(path) {
  return (await db.collection(path).get()).docs.map((document) => document.id).sort();
}

test("the other student keeps the one-to-one chat, and the account's markers go (#429)", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const other = await seedUser("other@epfl.ch", { accountType: "student" });
  const createdAt = new Date("2026-11-13T10:00:00Z");
  const chat = await seedChat(uid, other, {
    sent: { senderId: uid, text: "Fictional plan", createdAt },
    unsent: { senderId: uid, text: "", createdAt },
    reply: { senderId: other, text: "Fictional reply", createdAt },
  });
  for (const owner of [uid, other]) {
    await db.doc(`users/${owner}/hiddenChats/${chat.id}`).set({ hiddenUpTo: createdAt });
    await db.doc(`users/${owner}/hiddenGroupChats/e1~g1`).set({ hiddenUpTo: createdAt });
  }
  const idToken = await signIn("student@epfl.ch");

  assert.equal((await callDeleteAccount(idToken)).status, 200);

  assert.deepEqual((await chat.get()).data(), { uids: [other] });
  // The other student still finds it with the Chats tab's query.
  const listed = await db.collection("chats").where("uids", "array-contains", other).get();
  assert.deepEqual(listed.docs.map((document) => document.id), [chat.id]);
  const at = Timestamp.fromDate(createdAt);
  const message = async (id) => (await chat.collection("messages").doc(id).get()).data();
  assert.deepEqual(await message("sent"), { senderId: "", text: "Fictional plan", createdAt: at });
  assert.deepEqual(await message("unsent"), { senderId: "", text: "", createdAt: at });
  assert.deepEqual(await message("reply"), { senderId: other, text: "Fictional reply", createdAt: at });
  // users/{uid} was deleted first, which doesn't delete its subcollections: the step does.
  assert.deepEqual(await idsIn(`users/${uid}/hiddenChats`), []);
  assert.deepEqual(await idsIn(`users/${uid}/hiddenGroupChats`), []);
  assert.deepEqual(await idsIn(`users/${other}/hiddenChats`), [chat.id]);
  assert.deepEqual(await idsIn(`users/${other}/hiddenGroupChats`), ["e1~g1"]);
});

test("a one-to-one chat nobody is left in is deleted with its messages (#429)", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const createdAt = new Date("2026-11-13T10:00:00Z");
  // The friend's deletion wrote its marker, then stopped before its chats step: still in uids.
  await db.doc("deletedAccounts/fictional-friend").set({});
  const stopped = await seedChat(uid, "fictional-friend", {
    mine: { senderId: uid, text: "Fictional plan", createdAt },
    theirs: { senderId: "fictional-friend", text: "Fictional reply", createdAt },
  });
  // A deletion interrupted between a chat's messages and the chat: only the UID, no message left.
  const interrupted = db.doc(`chats/${[uid, "fictional-gone"].sort().join("~")}`);
  await interrupted.set({ uids: [uid] });
  const othersChat = await seedChat("fictional-friend", "fictional-third", {
    kept: { senderId: "fictional-third", text: "Fictional note", createdAt },
  });
  const idToken = await signIn("student@epfl.ch");

  assert.equal((await callDeleteAccount(idToken)).status, 200);

  assert.equal(await documentExists(stopped.path), false);
  assert.deepEqual(await idsIn(`${stopped.path}/messages`), []);
  assert.equal(await documentExists(interrupted.path), false);
  assert.deepEqual((await othersChat.get()).data(), { uids: ["fictional-friend", "fictional-third"] });
  assert.deepEqual(await idsIn(`${othersChat.path}/messages`), ["kept"]);
  assert.equal(await authUserExists(uid), false);
});

/** A Firestore error as a lost connection reports it. */
function lostConnection() {
  return Object.assign(new Error("Fictional failure"), { code: 14 });
}

/** The emulator's Firestore, with its collection-group queries on `group` failing. */
function failingCollectionGroup(group) {
  return new Proxy(db, {
    get(target, key) {
      if (key === "collectionGroup") {
        return (name) => {
          if (name === group) throw lostConnection();
          return target.collectionGroup(name);
        };
      }
      const value = Reflect.get(target, key);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

/**
 * Runs `run` with the first deletion of the document `path` failing, as a lost connection: the
 * chats step deletes a chat with its reference's `delete`, which nothing else in the handler uses.
 */
async function withFailingDelete(path, run) {
  const original = DocumentReference.prototype.delete;
  DocumentReference.prototype.delete = async function (...args) {
    if (this.path !== path) return original.apply(this, args);
    DocumentReference.prototype.delete = original;
    throw lostConnection();
  };
  try {
    return await run();
  } finally {
    DocumentReference.prototype.delete = original;
  }
}

/** Runs deleteAccount's handler for `uid`, signed in just now, on `firestore`. */
function deleteAs(uid, firestore = db) {
  return deleteAccountHandler({
    auth: { uid, token: { auth_time: 1_000_000 } },
    db: firestore,
    adminAuth: auth,
    now: () => 1_000_000 * 1000,
    logger: { error() {}, warn() {} },
  });
}

// Two friends deleting their accounts at about the same time, each stopping partway: whichever
// reaches its chats step first, with the other's marker written and their UID still in uids.
for (const lowerFirst of [true, false]) {
  test(`two friends deleting together, both stopping partway, leave nothing of their chat (${lowerFirst ? "lower" : "higher"} UID first, #429)`, async () => {
    const one = await seedUser("student@epfl.ch", { accountType: "student" });
    const two = await seedUser("other@epfl.ch", { accountType: "student" });
    const [first, second] = (one < two) === lowerFirst ? [one, two] : [two, one];
    const createdAt = new Date("2026-11-13T10:00:00Z");
    const theirs = await seedChat(first, second, {
      fromFirst: { senderId: first, text: "Fictional plan", createdAt },
      fromSecond: { senderId: second, text: "Fictional reply", createdAt },
    });
    const firstKept = await seedChat(first, "fictional-third", {
      a: { senderId: first, text: "Fictional note", createdAt },
    });
    const secondKept = await seedChat(second, "fictional-third", {
      b: { senderId: second, text: "Fictional note", createdAt },
    });
    for (const owner of [first, second]) {
      await db.doc(`users/${owner}/hiddenChats/${theirs.id}`).set({ hiddenUpTo: createdAt });
    }

    // Both stop right after writing their deletion marker.
    await assert.rejects(deleteAs(first, failingCollectionGroup("attendees")), { code: "unavailable" });
    await assert.rejects(deleteAs(second, failingCollectionGroup("attendees")), { code: "unavailable" });
    // The first retries and stops between their chat's messages and the chat: still listed.
    await assert.rejects(withFailingDelete(theirs.path, () => deleteAs(first)), { code: "unavailable" });
    assert.deepEqual((await theirs.get()).get("uids"), [first, second].sort());
    assert.deepEqual(await idsIn(`${theirs.path}/messages`), []);

    // The second's retry deletes the chat; the first's then finds nothing left of it.
    assert.deepEqual(await deleteAs(second), { deleted: true });
    assert.equal(await documentExists(theirs.path), false);
    assert.deepEqual(await deleteAs(first), { deleted: true });

    for (const [chat, id] of [[firstKept, "a"], [secondKept, "b"]]) {
      assert.deepEqual((await chat.get()).data(), { uids: ["fictional-third"] });
      assert.equal((await chat.collection("messages").doc(id).get()).get("senderId"), "");
    }
    for (const uid of [first, second]) {
      assert.deepEqual(await idsIn(`users/${uid}/hiddenChats`), []);
      assert.equal(await authUserExists(uid), false);
    }
  });
}

test("a deletion stopped after its chats step leaves nothing once the friend deletes too (#429)", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const friend = await seedUser("other@epfl.ch", { accountType: "student" });
  const createdAt = new Date("2026-11-13T10:00:00Z");
  const chat = await seedChat(uid, friend, {
    mine: { senderId: uid, text: "Fictional plan", createdAt },
    theirs: { senderId: friend, text: "Fictional reply", createdAt },
  });

  // The student leaves the chat, then stops at the messages step: their message still names them.
  await assert.rejects(deleteAs(uid, failingCollectionGroup("messages")), { code: "unavailable" });
  assert.deepEqual((await chat.get()).get("uids"), [friend]);
  assert.equal((await chat.collection("messages").doc("mine").get()).get("senderId"), uid);
  // The friend's deletion finds nobody else left, deletes the messages, then stops before the chat.
  await assert.rejects(withFailingDelete(chat.path, () => deleteAs(friend)), { code: "unavailable" });
  assert.deepEqual((await chat.get()).get("uids"), [friend]);
  assert.deepEqual(await idsIn(`${chat.path}/messages`), []);

  // Both retries finish: the student's has no message left to rewrite, the friend's deletes the chat.
  assert.deepEqual(await deleteAs(uid), { deleted: true });
  assert.deepEqual(await deleteAs(friend), { deleted: true });
  assert.equal(await documentExists(chat.path), false);
  assert.equal((await db.collectionGroup("messages").get()).size, 0);
  assert.equal(await authUserExists(uid), false);
  assert.equal(await authUserExists(friend), false);
});

test("an account without a profile is deleted", async () => {
  const uid = await seedUser("unverified@example.com", null);
  const idToken = await signIn("unverified@example.com");

  const { status, body } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  assert.deepEqual(body, { result: { deleted: true } });
  assert.equal(await authUserExists(uid), false);
});

test("account deletion removes group membership and markers, preserving other students", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const idToken = await signIn("student@epfl.ch");
  const shared = "events/shared/groups/g1";
  const solo = "events/solo/groups/g2";
  await db.doc(shared).set({ memberUids: [uid, "fictional-other"], createdBy: uid, capacity: 4 });
  await db.doc(solo).set({ memberUids: [uid], createdBy: uid, capacity: 4 });
  await db.doc(`events/shared/groupMembers/${uid}`).set({ groupId: "g1" });
  await db.doc("events/shared/groupMembers/fictional-other").set({ groupId: "g1" });
  await db.doc(`events/solo/groupMembers/${uid}`).set({ groupId: "g2" });
  assert.equal((await callDeleteAccount(idToken)).status, 200);
  assert.deepEqual((await db.doc(shared).get()).data(), {
    memberUids: ["fictional-other"], createdBy: uid, capacity: 4,
  });
  assert.equal(await documentExists(solo), false);
  assert.equal(await documentExists(`events/shared/groupMembers/${uid}`), false);
  assert.equal(await documentExists(`events/solo/groupMembers/${uid}`), false);
  assert.equal(await documentExists("events/shared/groupMembers/fictional-other"), true);
  assert.equal(await authUserExists(uid), false);
  assert.equal((await callDeleteAccount(idToken)).status, 200);
});

// The deleted user's ID token keeps its signature until it expires, and the callable doesn't
// check revocation, so a repeated call (a client retry after a lost answer) reaches the handler,
// which finds nothing left to delete and reports success again: the deletion is idempotent.
test("calling again with the same token after the deletion succeeds again", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const idToken = await signIn("student@epfl.ch");
  assert.equal((await callDeleteAccount(idToken)).status, 200);

  const { status, body } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  assert.deepEqual(body, { result: { deleted: true } });
  assert.equal(await authUserExists(uid), false);
});

test("an association is refused and keeps its profile and Auth user", async () => {
  const uid = await seedUser("club@example.org", {
    accountType: "association",
    isAssociationVerified: true,
  });
  const idToken = await signIn("club@example.org");

  const { status, body } = await callDeleteAccount(idToken);

  assert.equal(status, 400);
  assert.equal(body.error.status, "FAILED_PRECONDITION");
  assert.equal(body.error.details, DETAILS.ASSOCIATION);
  assert.equal(await documentExists(`users/${uid}`), true);
  assert.equal(await documentExists(`deletedAccounts/${uid}`), false);
  assert.equal(await authUserExists(uid), true);
});

test("a call without a signed-in user is refused and deletes nothing", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });

  const { status, body } = await callDeleteAccount(null);

  assert.equal(status, 401);
  assert.equal(body.error.status, "UNAUTHENTICATED");
  assert.equal(await documentExists(`users/${uid}`), true);
  assert.equal(await authUserExists(uid), true);
});

/** The fields of a Firestore REST document holding these strings and string lists. */
function restFields(fields) {
  return Object.fromEntries(
    Object.entries(fields).map(([key, value]) => [
      key,
      Array.isArray(value)
        ? { arrayValue: { values: value.map((item) => ({ stringValue: item })) } }
        : { stringValue: value },
    ]),
  );
}

/**
 * Saves the student's private and public profiles in one commit, as the profile step does, through
 * the Firestore emulator's REST API with `idToken`, so the Security Rules apply. Returns the HTTP
 * status: 200 when saved, 403 when the rules refuse it.
 */
async function saveProfilesAs(idToken, uid) {
  const base = `projects/${PROJECT_ID}/databases/(default)/documents`;
  const profile = { displayName: "Fictional Student", section: "IN", year: "BA3" };
  const documents = {
    [`users/${uid}`]: { uid, email: "student@epfl.ch", accountType: "student", ...profile },
    [`publicProfiles/${uid}`]: { ...profile, interests: [] },
  };
  const writes = Object.entries(documents).map(([path, fields]) => ({
    update: { name: `${base}/${path}`, fields: restFields(fields) },
  }));
  const response = await fetch(`http://${FIRESTORE_HOST}/v1/${base}:commit`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ writes }),
  });
  return response.status;
}

// The deleted user's ID token stays valid until it expires (see above), so another of their devices
// could save the profiles again from the profile step: between the profile cleanup and the Auth
// deletion, or after both. The public profile would then be readable by other students. The
// deletion marker deleteAccount writes makes the Security Rules refuse it (#229 review).

test("a profile save between the cleanup and the Auth deletion is refused", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const idToken = await signIn("student@epfl.ch");
  let statusDuringDeletion = null;
  // The real handler and Firestore; Auth tries the save just before deleting the user.
  const adminAuth = {
    async deleteUser(uidToDelete) {
      statusDuringDeletion = await saveProfilesAs(idToken, uid);
      await auth.deleteUser(uidToDelete);
    },
  };

  const result = await deleteAccountHandler({
    auth: { uid, token: { auth_time: 1_000_000 } },
    db,
    adminAuth,
    now: () => 1_000_000 * 1000,
    logger: { error: () => {} },
  });

  assert.deepEqual(result, { deleted: true });
  assert.equal(statusDuringDeletion, 403);
  assert.equal(await documentExists(`users/${uid}`), false);
  assert.equal(await documentExists(`publicProfiles/${uid}`), false);
  assert.equal(await authUserExists(uid), false);
});

test("the deleted user's token can't save the profiles again after the deletion", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const idToken = await signIn("student@epfl.ch");
  assert.equal((await callDeleteAccount(idToken)).status, 200);
  assert.equal(await documentExists(`deletedAccounts/${uid}`), true);

  assert.equal(await saveProfilesAs(idToken, uid), 403);
  assert.equal(await documentExists(`users/${uid}`), false);
  assert.equal(await documentExists(`publicProfiles/${uid}`), false);

  // Without the marker the same token can still save them, so the marker is what refuses it.
  await db.doc(`deletedAccounts/${uid}`).delete();
  assert.equal(await saveProfilesAs(idToken, uid), 200);
});

test("without a deletion, the same save succeeds", async () => {
  // The control for the two tests above: the REST save itself is accepted by the rules.
  await seedUser("student@epfl.ch", null);
  const idToken = await signIn("student@epfl.ch");
  const uid = (await auth.getUserByEmail("student@epfl.ch")).uid;
  assert.equal(await saveProfilesAs(idToken, uid), 200);
  assert.equal(await documentExists(`publicProfiles/${uid}`), true);
});


test("chat cleanup covers former/orphaned groups and resumes after a partially committed batch", async () => {
  const uid = await seedUser("student@epfl.ch", {accountType: "student"});
  const token = await signIn("student@epfl.ch");
  const createdAt = new Date("2026-11-13T10:00:00Z");
  const group = db.doc("events/shared/groups/g1");
  await group.set({memberUids: [uid, "fictional-other"], createdBy: uid, capacity: 4});
  await db.doc(`events/shared/groupMembers/${uid}`).set({groupId: "g1"});
  // No parent event/group is needed for these messages: cleanup must find chats left earlier too.
  for (let offset = 0; offset < 501; offset += 500) {
    const batch = db.batch();
    for (let i = offset; i < Math.min(offset + 500, 501); i++) {
      batch.set(db.doc(`events/former/groups/gone/messages/m${i}`), {
        senderId: uid, text: "Fictional plan", createdAt,
      });
    }
    await batch.commit();
  }
  const other = db.doc("events/shared/groups/g1/messages/other");
  await other.set({senderId: "fictional-other", text: "Reply", createdAt});
  let commits = 0;
  const failingDb = new Proxy(db, {get(target, key) {
    if (key === "batch") return () => {
      const batch = target.batch();
      const commit = batch.commit.bind(batch);
      batch.commit = async () => {
        if (++commits === 2) throw Object.assign(new Error("Fictional failure"), {code: 14});
        return commit();
      };
      return batch;
    };
    const value = Reflect.get(target, key);
    return typeof value === "function" ? value.bind(target) : value;
  }});
  await assert.rejects(deleteAccountHandler({
    auth: {uid, token: {auth_time: 1_000_000}}, db: failingDb, adminAuth: auth,
    now: () => 1_000_000_000, logger: {error() {}},
  }), error => error.code === "unavailable");
  assert.equal(await authUserExists(uid), true);
  assert.equal((await db.collectionGroup("messages").where("senderId", "==", uid).get()).size, 1);
  assert.equal((await db.collectionGroup("messages").where("senderId", "==", "").get()).size, 500);
  // The old token still has valid claims, but the marker already denies chat access.
  const read = await fetch(`http://${FIRESTORE_HOST}/v1/projects/${PROJECT_ID}/databases/(default)/documents/${other.path}`, {
    headers: {Authorization: `Bearer ${token}`},
  });
  assert.equal(read.status, 403);
  assert.equal((await callDeleteAccount(token)).status, 200);
  const retained = await db.collectionGroup("messages").where("senderId", "==", "").get();
  assert.equal(retained.size, 501);
  for (const message of retained.docs) {
    assert.deepEqual(message.data(), {senderId: "", text: "Fictional plan", createdAt: Timestamp.fromDate(createdAt)});
  }
  assert.deepEqual((await other.get()).data(), {senderId: "fictional-other", text: "Reply", createdAt: Timestamp.fromDate(createdAt)});
  assert.deepEqual((await group.get()).get("memberUids"), ["fictional-other"]);
  assert.equal(await documentExists(`events/shared/groupMembers/${uid}`), false);
  assert.equal(await authUserExists(uid), false);
  assert.equal((await callDeleteAccount(token)).status, 200);
});

test("a malformed group list does not block account deletion or expose data in logs", async () => {
  const uid = await seedUser("student@epfl.ch", {accountType: "student"});
  const token = await signIn("student@epfl.ch");
  const group = db.doc("events/shared/groups/malformed");
  await group.set({memberUids: [uid, 42, "fictional-other"]});
  await db.doc(`events/shared/groupMembers/${uid}`).set({groupId: "malformed"});
  assert.equal((await callDeleteAccount(token)).status, 200);
  assert.deepEqual((await group.get()).get("memberUids"), [42, "fictional-other"]);
  assert.equal(await authUserExists(uid), false);
  assert.equal(await documentExists(`events/shared/groupMembers/${uid}`), false);
});
