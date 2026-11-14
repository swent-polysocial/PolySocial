// Contributors: Claude (Fable 5.2, Anthropic) drafted the tests of the scheduled cleanup of unverified accounts, #226.
// Contributors: Claude (Fable 5.2, Anthropic) (the tests a mutation check of the handler found missing, #226).
"use strict";

// Unit tests with fakes, no emulator:
// node firebase/functions/deleteUnverifiedAccountsHandler.test.js
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { inspect } = require("node:util");
const { REGION } = require("./deleteAccountHandler");
const {
  UNVERIFIED_ACCOUNT_RETENTION_DAYS,
  deleteUnverifiedAccountsHandler,
} = require("./deleteUnverifiedAccountsHandler");
const { MAX_BATCH_WRITES } = require("./registrations");

// 03:00 in Zurich, when the function runs. Creation times have no milliseconds, like Auth's.
const NOW = Date.UTC(2026, 10, 14, 2, 0, 0);
const SECOND_MS = 1000;
const DAY_MS = 24 * 60 * 60 * SECOND_MS;
const RETENTION_MS = UNVERIFIED_ACCOUNT_RETENTION_DAYS * DAY_MS;
/** Old enough to be deleted when unverified. */
const OLD = RETENTION_MS + DAY_MS;
const SUMMARY = "deleteUnverifiedAccounts finished";
const STEP_FAILED = "deleteUnverifiedAccounts step failed";

/** A fictional Auth user created `age` milliseconds before NOW, as `listUsers` returns it. */
function account(uid, age, emailVerified = false) {
  return {
    uid,
    email: `${uid}@example.com`,
    emailVerified,
    metadata: { creationTime: new Date(NOW - age).toUTCString() },
  };
}

/** `count` unverified accounts old enough to be deleted, `a0000`, `a0001`… */
function abandonedAccounts(count) {
  return Array.from({ length: count }, (_, i) => account(`a${String(i).padStart(4, "0")}`, OLD));
}

/** An error as the Admin SDK reports it, with a message that must never be logged. */
function backendError(code) {
  return Object.assign(new Error("raw backend failure for a0000@example.com"), { code });
}

/**
 * A fake Firebase Auth holding `users` in order and a fake Firestore holding the paths in
 * `profiles`. `listUsers` returns at most `pageSize` users (and never more than asked) with the
 * last one's uid as the next page's token, as long as the page is full, like Auth: users deleted
 * in between are not skipped. `deleteUsers` deletes each user, counts a missing one as deleted, as
 * Auth does, and reports the error code `undeletable` holds for a uid instead of deleting it.
 * `calls` records every operation in order; `fail[operation][n]` makes the n-th call (from 1) of
 * `listUsers`, `deleteUsers` or `commit` throw. `logs` records every log entry.
 */
function fakeBackend({ users = [], profiles = [], pageSize = Infinity } = {}) {
  const order = users.map((user) => user.uid);
  const state = {
    users: new Map(users.map((user) => [user.uid, user])),
    profiles: new Set(profiles),
  };
  const calls = [];
  const fail = { listUsers: {}, deleteUsers: {}, commit: {} };
  const undeletable = new Map();
  /** Records the call, then throws if this call of `operation` was asked to fail. */
  const record = (operation, detail) => {
    calls.push(`${operation}:${detail}`);
    const error = fail[operation][calls.filter((call) => call.startsWith(`${operation}:`)).length];
    if (error) throw error;
  };
  const adminAuth = {
    async listUsers(maxResults, pageToken) {
      record("listUsers", pageToken ?? "");
      assert.ok(maxResults <= 1000, "listUsers returns at most 1000 users");
      const limit = Math.min(maxResults, pageSize);
      const start = pageToken === undefined ? 0 : order.indexOf(pageToken) + 1;
      const page = order
        .slice(start)
        .filter((uid) => state.users.has(uid))
        .slice(0, limit);
      const result = { users: page.map((uid) => state.users.get(uid)) };
      if (page.length === limit) result.pageToken = page.at(-1);
      return result;
    },
    async deleteUsers(uids) {
      record("deleteUsers", uids.length);
      assert.ok(uids.length <= 1000, "deleteUsers takes at most 1000 users");
      const errors = [];
      uids.forEach((uid, index) => {
        if (undeletable.has(uid)) errors.push({ index, error: backendError(undeletable.get(uid)) });
        else state.users.delete(uid);
      });
      return {
        successCount: uids.length - errors.length,
        failureCount: errors.length,
        errors,
      };
    },
  };
  const db = {
    collection: (collection) => ({ doc: (id) => ({ path: `${collection}/${id}` }) }),
    batch() {
      const deletes = [];
      return {
        delete: (ref) => deletes.push(ref.path),
        async commit() {
          record("commit", deletes.length);
          assert.ok(deletes.length <= MAX_BATCH_WRITES, "a commit holds at most 500 writes");
          for (const deleted of deletes) state.profiles.delete(deleted);
        },
      };
    },
  };
  const logs = [];
  const logger = {
    info: (...entry) => logs.push(["info", ...entry]),
    error: (...entry) => logs.push(["error", ...entry]),
  };
  const uids = () => [...state.users.keys()];
  return { adminAuth, db, logger, logs, calls, fail, undeletable, state, uids };
}

/** Runs the handler at NOW. */
function run(backend) {
  return deleteUnverifiedAccountsHandler({
    adminAuth: backend.adminAuth,
    db: backend.db,
    now: () => NOW,
    logger: backend.logger,
  });
}

/**
 * Runs the handler and asserts that it reports the run as failed, with a fixed message, after
 * logging exactly `failedSteps` (each `{ step, codes }`) and then the `summary`: so no uid, email
 * or raw error message is logged or thrown.
 */
async function assertFailedRun(backend, failedSteps, summary) {
  await assert.rejects(run(backend), {
    message: "deleteUnverifiedAccounts failed: see its failed steps.",
  });
  assert.deepEqual(backend.logs, [
    ...failedSteps.map((failedStep) => ["error", STEP_FAILED, failedStep]),
    ["info", SUMMARY, summary],
  ]);
}

test("an unverified account is deleted only once it is more than 7 days old", async () => {
  const backend = fakeBackend({
    users: [
      account("new", 0),
      account("a-second-younger", RETENTION_MS - SECOND_MS),
      account("exactly-7-days", RETENTION_MS),
      account("a-second-older", RETENTION_MS + SECOND_MS),
      account("a-month-old", 30 * DAY_MS),
    ],
  });

  assert.equal(await run(backend), 2);

  assert.deepEqual(backend.uids(), ["new", "a-second-younger", "exactly-7-days"]);
  // Only the counts are logged.
  assert.deepEqual(backend.logs, [["info", SUMMARY, { deleted: 2, failed: 0 }]]);
});

test("a verified account is never deleted, however old, and keeps its profile", async () => {
  const backend = fakeBackend({
    users: [account("verified", 365 * DAY_MS, true)],
    profiles: ["users/verified"],
  });

  assert.equal(await run(backend), 0);

  assert.deepEqual(backend.uids(), ["verified"]);
  assert.deepEqual([...backend.state.profiles], ["users/verified"]);
  assert.deepEqual(backend.calls, ["listUsers:"]);
});

test("an account whose verification or creation time can't be read is kept", async () => {
  const unreadable = (uid, changes) => ({ ...account(uid, OLD), ...changes });
  const backend = fakeBackend({
    users: [
      unreadable("no-metadata", { metadata: undefined }),
      unreadable("no-creation-time", { metadata: {} }),
      unreadable("bad-creation-time", { metadata: { creationTime: "not a date" } }),
      unreadable("no-verification-flag", { emailVerified: undefined }),
      unreadable("text-verification-flag", { emailVerified: "false" }),
    ],
  });

  assert.equal(await run(backend), 0);

  assert.equal(backend.uids().length, 5);
  assert.deepEqual(backend.calls, ["listUsers:"]);
});

// What the Admin SDK reports for a creation time it couldn't read. `new Date(null)` is 1970, so
// reading the time that way would delete the account as the oldest of all.
test("an account whose creation time the Admin SDK reports as null is kept", async () => {
  const backend = fakeBackend({
    users: [{ ...account("null-creation-time", OLD), metadata: { creationTime: null } }],
  });

  assert.equal(await run(backend), 0);

  assert.deepEqual(backend.uids(), ["null-creation-time"]);
  assert.deepEqual(backend.calls, ["listUsers:"]);
});

test("abandoned accounts are found on every page, the others are kept", async () => {
  const backend = fakeBackend({
    pageSize: 2,
    users: [
      account("a1", OLD),
      account("verified-1", OLD, true),
      account("young-1", DAY_MS),
      account("a2", OLD),
      account("a3", OLD),
      account("verified-2", OLD, true),
      account("a4", OLD),
    ],
  });

  assert.equal(await run(backend), 4);

  assert.deepEqual(backend.uids(), ["verified-1", "young-1", "verified-2"]);
  assert.deepEqual(backend.calls, [
    "listUsers:",
    "listUsers:verified-1",
    "listUsers:a2",
    "listUsers:verified-2",
    "commit:4",
    "deleteUsers:4",
  ]);
});

test("a profile stored for an abandoned account is deleted, before the account", async () => {
  const backend = fakeBackend({
    users: [account("a1", OLD), account("young", DAY_MS), account("verified", OLD, true)],
    profiles: ["users/a1", "users/young", "users/verified", "users/no-account"],
  });

  await run(backend);

  assert.deepEqual(
    [...backend.state.profiles],
    ["users/young", "users/verified", "users/no-account"],
  );
  assert.deepEqual(backend.calls, ["listUsers:", "commit:1", "deleteUsers:1"]);
});

test("more abandoned accounts than a batch holds are all deleted, batch by batch", async () => {
  const backend = fakeBackend({ users: [...abandonedAccounts(1001), account("young", DAY_MS)] });

  assert.equal(await run(backend), 1001);

  assert.deepEqual(backend.uids(), ["young"]);
  assert.deepEqual(backend.calls, [
    "listUsers:",
    "listUsers:a0999",
    "commit:500",
    "deleteUsers:500",
    "commit:500",
    "deleteUsers:500",
    "commit:1",
    "deleteUsers:1",
  ]);
});

test("a project without users deletes nothing and logs zeros", async () => {
  const backend = fakeBackend();

  assert.equal(await run(backend), 0);

  assert.deepEqual(backend.calls, ["listUsers:"]);
  assert.deepEqual(backend.logs, [["info", SUMMARY, { deleted: 0, failed: 0 }]]);
});

test("a page that can't be listed ends the listing, earlier accounts are deleted", async () => {
  const backend = fakeBackend({ users: abandonedAccounts(4), pageSize: 2 });
  backend.fail.listUsers[2] = backendError("auth/internal-error");

  await assertFailedRun(
    backend,
    [{ step: "list-users", codes: ["auth/internal-error"] }],
    { deleted: 2, failed: 0 },
  );

  assert.deepEqual(backend.uids(), ["a0002", "a0003"]);
  // The next day's run finds the accounts the failed one couldn't list.
  assert.equal(await run(backend), 2);
  assert.deepEqual(backend.uids(), []);
});

// Auth down or refusing the function: no account is found, which must not read as "none to delete".
test("when no page can be listed, nothing is deleted and the run fails", async () => {
  const backend = fakeBackend({ users: abandonedAccounts(2), profiles: ["users/a0000"] });
  backend.fail.listUsers[1] = backendError("auth/insufficient-permission");

  await assertFailedRun(
    backend,
    [{ step: "list-users", codes: ["auth/insufficient-permission"] }],
    { deleted: 0, failed: 0 },
  );

  assert.deepEqual(backend.calls, ["listUsers:"]);
  assert.deepEqual(backend.uids(), ["a0000", "a0001"]);
  assert.deepEqual([...backend.state.profiles], ["users/a0000"]);
});

test("a batch whose accounts can't be deleted doesn't stop the next batch", async () => {
  const backend = fakeBackend({ users: abandonedAccounts(MAX_BATCH_WRITES + 2) });
  backend.fail.deleteUsers[1] = backendError("auth/quota-exceeded");

  await assertFailedRun(
    backend,
    [{ step: "delete-auth-users", codes: ["auth/quota-exceeded"] }],
    { deleted: 2, failed: MAX_BATCH_WRITES },
  );

  assert.equal(backend.uids().length, MAX_BATCH_WRITES);
  assert.ok(!backend.state.users.has("a0500") && !backend.state.users.has("a0501"));
  // The next day's run deletes the batch that failed.
  assert.equal(await run(backend), MAX_BATCH_WRITES);
  assert.deepEqual(backend.uids(), []);
});

test("a batch whose profiles can't be deleted keeps its accounts for the next run", async () => {
  const backend = fakeBackend({
    users: abandonedAccounts(MAX_BATCH_WRITES + 1),
    profiles: ["users/a0000", "users/a0500"],
  });
  backend.fail.commit[1] = backendError(14);

  await assertFailedRun(
    backend,
    [{ step: "delete-profiles", codes: [14] }],
    { deleted: 1, failed: MAX_BATCH_WRITES },
  );

  // No account of the failed batch was deleted, so none is left without its profile's deletion.
  assert.equal(backend.uids().length, MAX_BATCH_WRITES);
  assert.deepEqual([...backend.state.profiles], ["users/a0000"]);
  assert.deepEqual(backend.calls, ["listUsers:", "commit:500", "commit:1", "deleteUsers:1"]);
  assert.equal(await run(backend), MAX_BATCH_WRITES);
  assert.deepEqual([...backend.state.profiles], []);
});

test("accounts deleteUsers reports as failed are counted, the others are deleted", async () => {
  const backend = fakeBackend({ users: abandonedAccounts(5) });
  backend.undeletable.set("a0001", "auth/internal-error");
  backend.undeletable.set("a0003", "auth/internal-error");
  backend.undeletable.set("a0004", "auth/insufficient-permission");

  await assertFailedRun(
    backend,
    [{ step: "delete-auth-users", codes: ["auth/internal-error", "auth/insufficient-permission"] }],
    { deleted: 2, failed: 3 },
  );

  assert.deepEqual(backend.uids(), ["a0001", "a0003", "a0004"]);
});

test("a run that fails touches no verified or recent account, nor their documents", async () => {
  const keptAccounts = ["verified", "recent", "association-in-review"];
  // An association whose email is verified waits for its review with both its documents: only
  // the email counts, and the cleanup can't read a profile from this fake Firestore.
  const keptDocuments = [
    "users/verified",
    "users/recent",
    "users/association-in-review",
    "associations/association-in-review",
  ];
  const failures = {
    "a page that can't be listed": (backend) => {
      backend.fail.listUsers[2] = backendError("auth/internal-error");
    },
    "profiles that can't be deleted": (backend) => {
      backend.fail.commit[1] = backendError(14);
    },
    "accounts that can't be deleted": (backend) => {
      backend.fail.deleteUsers[1] = backendError("auth/quota-exceeded");
    },
    "one account that can't be deleted": (backend) => {
      backend.undeletable.set("a0001", "auth/internal-error");
    },
  };

  for (const [failure, inject] of Object.entries(failures)) {
    const [abandoned1, abandoned2, abandoned3] = abandonedAccounts(3);
    const backend = fakeBackend({
      pageSize: 2,
      users: [
        account("verified", OLD, true),
        abandoned1,
        account("recent", DAY_MS),
        abandoned2,
        account("association-in-review", OLD, true),
        abandoned3,
      ],
      profiles: [...keptDocuments, "users/a0000"],
    });
    inject(backend);

    await assert.rejects(run(backend), Error, failure);

    assert.deepEqual(
      backend.uids().filter((uid) => keptAccounts.includes(uid)),
      keptAccounts,
      failure,
    );
    assert.deepEqual(
      [...backend.state.profiles].filter((path) => path !== "users/a0000"),
      keptDocuments,
      failure,
    );
  }
});

test("the error of a failed run holds no uid, email or backend error message", async () => {
  const backend = fakeBackend({
    pageSize: 1,
    users: [account("abandoned-uid", OLD), account("unlisted-uid", OLD)],
  });
  // Both kinds of error the run meets: one thrown, one reported for an account.
  backend.fail.listUsers[2] = backendError("auth/internal-error");
  backend.undeletable.set("abandoned-uid", "auth/internal-error");

  const error = await run(backend).then(
    () => assert.fail("the run should have failed"),
    (thrown) => thrown,
  );

  // Everything a log of the error would print: its message, stack, cause and any other field.
  const printed = inspect(error, { depth: null, showHidden: true });
  for (const secret of ["abandoned-uid", "example.com", "raw backend failure"]) {
    assert.ok(!printed.includes(secret), `the error thrown holds "${secret}"`);
  }
});

// The manifest the Firebase CLI deploys from: a wrong option here deploys the function elsewhere
// or at another time without any other test noticing.
test("the function runs daily, on Zurich time, in deleteAccount's region", () => {
  const { deleteUnverifiedAccounts } = require("./index");
  const endpoint = deleteUnverifiedAccounts.__endpoint;

  assert.equal(endpoint.scheduleTrigger.schedule, "every day 03:00");
  assert.equal(endpoint.scheduleTrigger.timeZone, "Europe/Zurich");
  assert.deepEqual(endpoint.region, [REGION]);
});
