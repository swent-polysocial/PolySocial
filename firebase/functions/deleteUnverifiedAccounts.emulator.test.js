// Contributors: Claude (Fable 5.2, Anthropic) drafted the emulator test of the scheduled cleanup of unverified accounts, #226.
// Contributors: Claude (Fable 5.2, Anthropic) (the exported function called as Cloud Scheduler does, after a mutation check, #226).
"use strict";

// Runs the cleanup of unverified accounts on the Auth and Firestore emulators, through the Admin
// SDK's real listUsers, deleteUsers and batches. It calls the handler directly: the Functions
// emulator only runs a scheduled function through the Pub/Sub emulator, which isn't started. One
// test calls the function index.js exports instead, in this process, the way Cloud Scheduler calls
// it over HTTP. Run from the repository root (see the README):
// firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node --test --test-concurrency=1 firebase/functions/*.emulator.test.js"
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { deleteApp, getApps, initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const {
  UNVERIFIED_ACCOUNT_RETENTION_DAYS,
  deleteUnverifiedAccountsHandler,
} = require("./deleteUnverifiedAccountsHandler");

// A "demo-" project never reaches a real Firebase project.
const PROJECT_ID = "demo-polysocial";
// emulators:exec sets these for the emulators it starts.
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;
const AUTH_HOST = process.env.FIREBASE_AUTH_EMULATOR_HOST;
const DAY_MS = 24 * 60 * 60 * 1000;
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

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, "deleteUnverifiedAccounts");
  db = getFirestore(app);
  auth = getAuth(app);
});

after(async () => {
  // The app of these tests and the default one index.js creates.
  await Promise.all(getApps().map((created) => deleteApp(created)));
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
 * A fictional account created `daysAgo` days ago, to import: an import chooses the creation time,
 * which Auth sets to now for an account that signs up.
 */
function account(uid, daysAgo, emailVerified = false) {
  const createdAt = new Date(Date.now() - daysAgo * DAY_MS).toUTCString();
  return {
    uid,
    email: `${uid}@example.com`,
    emailVerified,
    metadata: { creationTime: createdAt, lastSignInTime: createdAt },
  };
}

/** Imports the accounts with their creation times, at most 1000 per call as Auth requires. */
async function importAccounts(accounts) {
  for (let start = 0; start < accounts.length; start += 1000) {
    const result = await auth.importUsers(accounts.slice(start, start + 1000));
    if (result.failureCount > 0) {
      throw new Error(`Could not import ${result.failureCount} accounts.`);
    }
  }
}

/** Runs the cleanup now and returns the number of accounts deleted and everything it logged. */
async function runCleanup() {
  const logs = [];
  const logger = {
    info: (...entry) => logs.push(["info", ...entry]),
    error: (...entry) => logs.push(["error", ...entry]),
  };
  const deleted = await deleteUnverifiedAccountsHandler({
    adminAuth: auth,
    db,
    now: Date.now,
    logger,
  });
  return { deleted, logs };
}

/** The uids of every account left, sorted. */
async function accountsLeft() {
  const uids = [];
  let pageToken;
  do {
    const page = await auth.listUsers(1000, pageToken);
    uids.push(...page.users.map((user) => user.uid));
    pageToken = page.pageToken;
  } while (pageToken);
  return uids.sort();
}

async function documentExists(path) {
  return (await db.doc(path).get()).exists;
}

/**
 * Calls the function index.js exports with a request and a response, the way Cloud Scheduler's
 * HTTP call reaches it, and returns the status it answers: 200 once the cleanup is done, 500 when
 * it failed. index.js creates the Admin SDK's default app, which takes its project from
 * GCLOUD_PROJECT and reaches the emulators through the hosts checked above.
 */
async function callAsCloudScheduler() {
  if (process.env.GCLOUD_PROJECT !== PROJECT_ID) {
    throw new Error(
      `GCLOUD_PROJECT is not ${PROJECT_ID}: run this test inside ` +
        "`firebase emulators:exec --project demo-polysocial ...`, as the README says.",
    );
  }
  const { deleteUnverifiedAccounts } = require("./index");
  let status;
  const response = {
    status(code) {
      status = code;
      return response;
    },
    send() {},
  };
  await deleteUnverifiedAccounts({ headers: {}, header: () => undefined }, response);
  return status;
}

test("an abandoned account and its profile are deleted, other accounts are kept", async () => {
  await importAccounts([
    account("abandoned", UNVERIFIED_ACCOUNT_RETENTION_DAYS + 1),
    account("verified", UNVERIFIED_ACCOUNT_RETENTION_DAYS + 1, true),
    account("recent", UNVERIFIED_ACCOUNT_RETENTION_DAYS - 1),
  ]);
  // Signed up just now, as the app does: Auth sets the creation time.
  const fresh = await auth.createUser({ email: "fresh@example.com", password: PASSWORD });
  for (const uid of ["abandoned", "verified", "recent"]) {
    await db.doc(`users/${uid}`).set({ uid, email: `${uid}@example.com` });
  }

  const { deleted, logs } = await runCleanup();

  assert.equal(deleted, 1);
  assert.deepEqual(logs, [
    ["info", "deleteUnverifiedAccounts finished", { deleted: 1, failed: 0 }],
  ]);
  assert.deepEqual(await accountsLeft(), [fresh.uid, "recent", "verified"].sort());
  assert.equal(await documentExists("users/abandoned"), false);
  assert.equal(await documentExists("users/verified"), true);
  assert.equal(await documentExists("users/recent"), true);
});

test("the address of a deleted account can sign up again", async () => {
  await importAccounts([account("mistyped", UNVERIFIED_ACCOUNT_RETENTION_DAYS + 1)]);
  const signUp = () => auth.createUser({ email: "mistyped@example.com", password: PASSWORD });
  await assert.rejects(signUp(), (error) => error.code === "auth/email-already-exists");

  await runCleanup();

  const owner = await signUp();
  assert.notEqual(owner.uid, "mistyped");
});

test("accounts over several pages and batches are all deleted", async () => {
  // More than listUsers returns at once (1000) and than two batches hold (500 each).
  const abandoned = Array.from({ length: 1001 }, (_, i) =>
    account(`abandoned-${i}`, UNVERIFIED_ACCOUNT_RETENTION_DAYS + 1),
  );
  await importAccounts([...abandoned, account("verified", 30, true), account("recent", 1)]);

  assert.equal((await runCleanup()).deleted, 1001);

  assert.deepEqual(await accountsLeft(), ["recent", "verified"]);
});

test("a second run, on a project left without accounts, deletes nothing", async () => {
  await importAccounts([account("abandoned", UNVERIFIED_ACCOUNT_RETENTION_DAYS + 1)]);
  assert.equal((await runCleanup()).deleted, 1);

  assert.equal((await runCleanup()).deleted, 0);
});

// The only test that runs index.js's own code for this function: the Functions emulator ignores a
// scheduled function (see above), and every other test gives the handler its own arguments.
test("the scheduled function, called as Cloud Scheduler calls it, runs the cleanup", async () => {
  await importAccounts([
    account("abandoned", UNVERIFIED_ACCOUNT_RETENTION_DAYS + 1),
    account("verified", UNVERIFIED_ACCOUNT_RETENTION_DAYS + 1, true),
    account("recent", UNVERIFIED_ACCOUNT_RETENTION_DAYS - 1),
  ]);
  for (const uid of ["abandoned", "verified"]) {
    await db.doc(`users/${uid}`).set({ uid, email: `${uid}@example.com` });
  }

  assert.equal(await callAsCloudScheduler(), 200);

  assert.deepEqual(await accountsLeft(), ["recent", "verified"]);
  assert.equal(await documentExists("users/abandoned"), false);
  assert.equal(await documentExists("users/verified"), true);
});
