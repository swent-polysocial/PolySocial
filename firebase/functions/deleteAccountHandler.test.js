// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount handler tests, #206; Mohamed Khellaf (reviewed and revised).
"use strict";

// Unit tests with fakes, no emulator: node firebase/functions/deleteAccountHandler.test.js
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { HttpsError } = require("firebase-functions/v2/https");
const {
  DETAILS,
  MAX_AUTH_AGE_MS,
  REGION,
  deleteAccountHandler,
} = require("./deleteAccountHandler");

const UID = "student-uid-1";
const NOW = Date.UTC(2026, 9, 28, 12, 0, 0);
const RECENT_AUTH_TIME = NOW / 1000 - 60;

/** An error as the Admin SDK reports it: a gRPC status code for Firestore, a string for Auth. */
function backendError(code) {
  return Object.assign(new Error(`raw backend failure for ${UID}`), { code });
}

/**
 * A fake Firestore holding at most the user's profile, and a fake Auth. `calls` records every
 * operation in order; a failure set in `fail` makes that operation throw (once, when `once`).
 */
function fakeBackend({ profile = { accountType: "student" }, authUserExists = true } = {}) {
  const calls = [];
  const fail = {};
  const state = { profile, authUserExists };
  const failIfAsked = (operation) => {
    const error = fail[operation];
    if (error) {
      if (error.once) delete fail[operation];
      throw error.error;
    }
  };
  const db = {
    collection(path) {
      calls.push(`collection:${path}`);
      return {
        doc(id) {
          return { path: `${path}/${id}` };
        },
      };
    },
    async runTransaction(updateFunction) {
      const deletes = [];
      await updateFunction({
        async get(ref) {
          calls.push(`get:${ref.path}`);
          failIfAsked("get");
          const data = state.profile;
          return {
            exists: data !== null,
            get: (field) => (data === null ? undefined : data[field]),
          };
        },
        delete(ref) {
          deletes.push(ref);
        },
      });
      for (const ref of deletes) {
        calls.push(`delete:${ref.path}`);
        failIfAsked("delete");
        state.profile = null;
      }
    },
  };
  const adminAuth = {
    async deleteUser(uid) {
      calls.push(`deleteUser:${uid}`);
      failIfAsked("deleteUser");
      if (!state.authUserExists) throw backendError("auth/user-not-found");
      state.authUserExists = false;
    },
  };
  // Records every log entry, at any level, so a test can check exactly what was logged.
  const logs = [];
  const logger = {};
  for (const level of ["debug", "log", "info", "warn", "error"]) {
    logger[level] = (...entry) => logs.push([level, ...entry]);
  }
  return { db, adminAuth, logger, logs, calls, fail, state };
}

/** Calls the handler as `auth` (undefined: nobody signed in). */
function callAs(backend, auth) {
  return deleteAccountHandler({
    auth,
    db: backend.db,
    adminAuth: backend.adminAuth,
    now: () => NOW,
    logger: backend.logger,
  });
}

/** Calls the handler as the student, signed in a minute ago. */
function call(backend) {
  return callAs(backend, { uid: UID, token: { auth_time: RECENT_AUTH_TIME } });
}

/** Asserts that `promise` rejects with an HttpsError of this code and details, with no uid. */
async function assertHttpsError(promise, code, details) {
  await assert.rejects(promise, (error) => {
    assert.ok(error instanceof HttpsError, `expected an HttpsError, got ${error}`);
    assert.equal(error.code, code);
    assert.equal(error.details, details);
    assert.ok(!error.message.includes(UID), "the message must not contain the uid");
    assert.ok(!error.message.includes("raw backend failure"), "the message must be generic");
    return true;
  });
}

test("the region is the one the Android client calls", () => {
  assert.equal(REGION, "europe-west6");
});

test("a call without a signed-in user is unauthenticated and deletes nothing", async () => {
  const backend = fakeBackend();
  await assertHttpsError(callAs(backend, undefined), "unauthenticated", undefined);
  await assertHttpsError(callAs(backend, { uid: "", token: {} }), "unauthenticated", undefined);
  assert.deepEqual(backend.calls, []);
});

test("a sign-in older than 5 minutes needs a recent login and deletes nothing", async () => {
  const backend = fakeBackend();
  const staleAuthTime = (NOW - MAX_AUTH_AGE_MS) / 1000 - 1;
  await assertHttpsError(
    callAs(backend, { uid: UID, token: { auth_time: staleAuthTime } }),
    "failed-precondition",
    DETAILS.RECENT_LOGIN_REQUIRED,
  );
  assert.deepEqual(backend.calls, []);
});

test("a sign-in exactly 5 minutes old is still recent enough", async () => {
  const backend = fakeBackend();
  const boundaryAuthTime = (NOW - MAX_AUTH_AGE_MS) / 1000;
  assert.deepEqual(await callAs(backend, { uid: UID, token: { auth_time: boundaryAuthTime } }), {
    deleted: true,
  });
});

test("a token without a usable auth_time counts as a stale sign-in", async () => {
  for (const token of [{}, { auth_time: "123" }, { auth_time: Number.NaN }, undefined]) {
    const backend = fakeBackend();
    await assertHttpsError(
      callAs(backend, { uid: UID, token }),
      "failed-precondition",
      DETAILS.RECENT_LOGIN_REQUIRED,
    );
    assert.deepEqual(backend.calls, []);
  }
});

test("an association is refused and keeps its profile and Auth user", async () => {
  for (const isAssociationVerified of [true, false]) {
    const backend = fakeBackend({ profile: { accountType: "association", isAssociationVerified } });
    await assertHttpsError(call(backend), "failed-precondition", DETAILS.ASSOCIATION);
    assert.deepEqual(backend.calls, ["collection:users", `get:users/${UID}`]);
    assert.notEqual(backend.state.profile, null);
    assert.equal(backend.state.authUserExists, true);
  }
});

test("a student's profile is deleted, then the Auth user, and only then success", async () => {
  const backend = fakeBackend();
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.calls, [
    "collection:users",
    `get:users/${UID}`,
    `delete:users/${UID}`,
    `deleteUser:${UID}`,
  ]);
  assert.equal(backend.state.profile, null);
  assert.equal(backend.state.authUserExists, false);
});

test("a profile without accountType is a student's and is deleted", async () => {
  const backend = fakeBackend({ profile: { displayName: "Student One" } });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(backend.state.profile, null);
  assert.equal(backend.state.authUserExists, false);
});

test("an account without a profile (never verified) can delete itself", async () => {
  const backend = fakeBackend({ profile: null });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(backend.state.authUserExists, false);
});

test("only the users collection is touched, never the user's events", async () => {
  const backend = fakeBackend();
  await call(backend);
  const collections = backend.calls.filter((c) => c.startsWith("collection:"));
  assert.deepEqual(collections, ["collection:users"]);
  assert.ok(backend.calls.every((c) => !c.includes("events")));
});

test("a profile read that can't reach Firestore is unavailable and deletes nothing", async () => {
  for (const code of [14, 4, "unavailable", "ECONNRESET", "app/network-error"]) {
    const backend = fakeBackend();
    backend.fail.get = { error: backendError(code) };
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.notEqual(backend.state.profile, null);
    assert.equal(backend.state.authUserExists, true);
    assert.ok(!backend.calls.some((c) => c.startsWith("deleteUser")));
  }
});

test("a transaction aborted by concurrent writes is unavailable and deletes nothing", async () => {
  for (const code of [10, "aborted"]) {
    const backend = fakeBackend();
    backend.fail.delete = { error: backendError(code) };
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.notEqual(backend.state.profile, null);
    assert.equal(backend.state.authUserExists, true);
    assert.ok(!backend.calls.some((c) => c.startsWith("deleteUser")));
  }
});

test("any other profile read failure is internal and deletes nothing", async () => {
  for (const error of [backendError(7), new TypeError("unexpected"), "not an Error"]) {
    const backend = fakeBackend();
    backend.fail.get = { error };
    await assertHttpsError(call(backend), "internal", undefined);
    assert.notEqual(backend.state.profile, null);
    assert.equal(backend.state.authUserExists, true);
  }
});

test("a failed profile deletion leaves the Auth user untouched", async () => {
  const backend = fakeBackend();
  backend.fail.delete = { error: backendError(14) };
  await assertHttpsError(call(backend), "unavailable", undefined);
  assert.notEqual(backend.state.profile, null);
  assert.equal(backend.state.authUserExists, true);
  assert.ok(!backend.calls.some((c) => c.startsWith("deleteUser")));
});

test("a failed Auth deletion after the profile is gone is an error, not success", async () => {
  const unavailable = fakeBackend();
  unavailable.fail.deleteUser = { error: backendError("app/network-timeout") };
  await assertHttpsError(call(unavailable), "unavailable", undefined);
  assert.equal(unavailable.state.profile, null);
  assert.equal(unavailable.state.authUserExists, true);

  const internal = fakeBackend();
  internal.fail.deleteUser = { error: backendError("auth/internal-error") };
  await assertHttpsError(call(internal), "internal", undefined);
  assert.equal(internal.state.authUserExists, true);
});

test("a retry after a failed Auth deletion finishes the deletion", async () => {
  const backend = fakeBackend();
  backend.fail.deleteUser = { error: backendError(14), once: true };
  await assertHttpsError(call(backend), "unavailable", undefined);
  assert.equal(backend.state.profile, null);

  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(backend.state.authUserExists, false);
});

test("a failed step logs only its name and the error code, a success logs nothing", async () => {
  const succeeded = fakeBackend();
  await call(succeeded);
  assert.deepEqual(succeeded.logs, []);

  const refused = fakeBackend({ profile: { accountType: "association" } });
  await assertHttpsError(call(refused), "failed-precondition", DETAILS.ASSOCIATION);
  assert.deepEqual(refused.logs, []);

  const profileFailed = fakeBackend();
  profileFailed.fail.get = { error: backendError(14) };
  await assertHttpsError(call(profileFailed), "unavailable", undefined);
  assert.deepEqual(profileFailed.logs, [
    ["error", "deleteAccount step failed", { step: "delete-profile", code: 14 }],
  ]);

  const authFailed = fakeBackend();
  authFailed.fail.deleteUser = { error: backendError("auth/internal-error") };
  await assertHttpsError(call(authFailed), "internal", undefined);
  assert.deepEqual(authFailed.logs, [
    [
      "error",
      "deleteAccount step failed",
      { step: "delete-auth-user", code: "auth/internal-error" },
    ],
  ]);

  const noCode = fakeBackend();
  noCode.fail.get = { error: new TypeError(`unexpected for ${UID}`) };
  await assertHttpsError(call(noCode), "internal", undefined);
  assert.deepEqual(noCode.logs, [
    ["error", "deleteAccount step failed", { step: "delete-profile", code: null }],
  ]);
  for (const backend of [profileFailed, authFailed, noCode]) {
    const logged = JSON.stringify(backend.logs);
    assert.ok(!logged.includes(UID), "the log must not contain the uid");
    assert.ok(!logged.includes("raw backend failure"), "the log must not hold the error message");
  }
});

test("a retry after a complete deletion succeeds again", async () => {
  const backend = fakeBackend({ profile: null, authUserExists: false });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(backend.calls.at(-1), `deleteUser:${UID}`);
});
