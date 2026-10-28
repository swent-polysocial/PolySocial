// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount emulator test, #206; Mohamed Khellaf (reviewed and revised).
"use strict";

// Calls the deployed-to-emulator deleteAccount over HTTP, as the Android client does, against the
// Auth, Firestore and Functions emulators. Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node firebase/functions/deleteAccount.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { DETAILS, REGION } = require("./deleteAccountHandler");

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
  try {
    await fetch(FUNCTIONS_ORIGIN);
  } catch (error) {
    throw new Error(
      `The Functions emulator does not answer at ${FUNCTIONS_ORIGIN}: add "functions" to --only.`,
      { cause: error },
    );
  }
  app = initializeApp({ projectId: PROJECT_ID });
  db = getFirestore(app);
  auth = getAuth(app);
});

after(async () => {
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

/** Creates a fictional verified user, with a profile when `profile` is set, and an event. */
async function seedUser(email, profile) {
  const { uid } = await auth.createUser({ email, password: PASSWORD, emailVerified: true });
  if (profile) await db.doc(`users/${uid}`).set({ uid, email, ...profile });
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

test("a student's profile and Auth user are deleted, their event is kept", async () => {
  const uid = await seedUser("student@epfl.ch", { accountType: "student" });
  const idToken = await signIn("student@epfl.ch");

  const { status, body } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  assert.deepEqual(body, { result: { deleted: true } });
  assert.equal(await documentExists(`users/${uid}`), false);
  assert.equal(await authUserExists(uid), false);
  assert.equal(await documentExists(`events/event-of-${uid}`), true);
});

test("an account without a profile is deleted", async () => {
  const uid = await seedUser("unverified@example.com", null);
  const idToken = await signIn("unverified@example.com");

  const { status, body } = await callDeleteAccount(idToken);

  assert.equal(status, 200);
  assert.deepEqual(body, { result: { deleted: true } });
  assert.equal(await authUserExists(uid), false);
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
