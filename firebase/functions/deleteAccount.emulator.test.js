// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount emulator test, #206; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (the public profile deleted with the account, #229; profiles can't be saved
// again once deleted, after review, #229).
"use strict";

// Calls the deployed-to-emulator deleteAccount over HTTP, as the Android client does, against the
// Auth, Firestore and Functions emulators. Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node firebase/functions/deleteAccount.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { DETAILS, REGION, deleteAccountHandler } = require("./deleteAccountHandler");

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
