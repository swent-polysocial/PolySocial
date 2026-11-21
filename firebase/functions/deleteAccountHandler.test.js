// Contributors: Claude (Opus 5.5, Anthropic) drafted the deleteAccount handler tests, #206; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (the public profile deleted with the account, written test-first, #229;
// the deletion marker, test-first, after review, #229; the user's registrations, #275).
"use strict";
// Contributors: OpenAI Codex (group cleanup, partial failure and retry regression tests, #241).
// Contributors: Claude Opus 5.5 (the user's friendships, written test-first, #357); Konstantinos
// Perdikeas (directed, reviewed and owns them).
// Contributors: Claude (Opus 5.5, Anthropic) (the attendeeCount each deleted registration lowers,
// #405).
// Contributors: Claude (Opus 5.5, Anthropic) (the one-to-one chats and delete-for-me markers, and
// the chats, markers and other accounts' deletion markers in the fake, #429).
// Contributors: Claude (Opus 5.5, Anthropic) (the fake's query limit, more chats than a page, and a
// failure at every call followed by a retry, #429).
// Contributors: Claude (Opus 5.5, Anthropic) (a message deleted while the messages step runs, and
// malformed uids, after review, #429).

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
const OTHER_REGISTRATION = "events/e1/attendees/student-uid-2";
/**
 * The calls deleting the student's two registrations, as the default fake backend holds them: each
 * in its own transaction, which reads the registration and its event, deletes the registration and
 * lowers the event's attendeeCount (#405).
 */
const REGISTRATION_CALLS = [
  "collectionGroup:attendees",
  `query:attendees uid == ${UID}`,
  `getAll:events/e1/attendees/${UID},events/e1`,
  `delete:events/e1/attendees/${UID}`,
  "update:events/e1",
  `getAll:events/e2/attendees/${UID},events/e2`,
  `delete:events/e2/attendees/${UID}`,
  "update:events/e2",
  `query:attendees uid == ${UID}`,
];
/** Another pair's friendship, which deleting the student must keep. */
const OTHER_FRIENDSHIP = "friendships/student-uid-2~student-uid-3";
/** The calls deleting the student's two friendships, as the default fake backend holds them. */
const FRIENDSHIP_CALLS = [
  `query:friendships uids array-contains ${UID}`,
  `commit:friendships/student-uid-0~${UID},friendships/${UID}~student-uid-2`,
  `query:friendships uids array-contains ${UID}`,
];
/** The calls of the chats step for a student without chats or delete-for-me markers (#429). */
const CHAT_CALLS = [
  "collection:chats",
  `query:chats uids array-contains ${UID}`,
  `collection:users/${UID}/hiddenChats`,
  `list:users/${UID}/hiddenChats`,
  `collection:users/${UID}/hiddenGroupChats`,
  `list:users/${UID}/hiddenGroupChats`,
];

/** An error as the Admin SDK reports it: a gRPC status code for Firestore, a string for Auth. */
function backendError(code) {
  return Object.assign(new Error(`raw backend failure for ${UID}`), { code });
}

/**
 * A fake Firestore holding at most the user's private and public profiles, deletion marker and
 * registrations (paths `events/{eventId}/attendees/{uid}`, also other students') with their events'
 * `attendeeCount` (`events`, by path), one-to-one chats (`chats`, by path, with their `uids`; their
 * messages in `messages` under `chats/{pairId}/messages/{id}`), other accounts' deletion markers
 * (`deletedUids`) and delete-for-me markers (`hidden`, by path, also other students'), and a fake
 * Auth.
 * A transaction's writes are applied together at its end, or none of them when one fails, as in
 * Firestore. `calls` records every operation in order; a failure set in `fail`
 * makes that operation throw (once, when `once`; only after it succeeded `after` times, if set).
 */
function fakeBackend({
  profile = { accountType: "student" },
  publicProfile = { displayName: "Student One", section: "IN", year: "BA3", interests: [] },
  authUserExists = true,
  groups = {},
  markers = {},
  messages = {},
  beforeGroupRead = () => {},
  registrations = [`events/e1/attendees/${UID}`, `events/e2/attendees/${UID}`, OTHER_REGISTRATION],
  // Friendships by path, with their uids: a request the student sent, a friend, another pair's.
  friendships = {
    [`friendships/student-uid-0~${UID}`]: ["student-uid-0", UID],
    [`friendships/${UID}~student-uid-2`]: [UID, "student-uid-2"],
    [OTHER_FRIENDSHIP]: ["student-uid-2", "student-uid-3"],
  },
  events = { "events/e1": { attendeeCount: 2 }, "events/e2": { attendeeCount: 1 } },
  chats = {},
  deletedUids = [],
  hidden = {},
  beforeChatRead = () => {},
  beforeAnonymise = () => {},
} = {}) {
  const calls = [];
  const fail = {};
  const state = {
    groups, markers, messages,
    profile,
    publicProfile,
    deletionMarker: null,
    authUserExists,
    registrations: new Set(registrations),
    friendships: { ...friendships },
    events,
    chats,
    deletedUids: new Set(deletedUids),
    hidden,
  };
  const failIfAsked = (operation) => {
    const error = fail[operation];
    if (error) {
      if (error.after > 0) {
        error.after -= 1;
        return;
      }
      if (error.once) delete fail[operation];
      throw error.error;
    }
  };
  // A chat's reference, as the chats query returns it: its messages and its own deletion.
  const chatRef = (path) => ({
    path,
    collection: (name) => db.collection(`${path}/${name}`),
    async delete() {
      calls.push(`delete:${path}`);
      failIfAsked("chat-delete");
      delete state.chats[path];
    },
  });
  // The documents directly under the collection `path`, from the map `store`.
  const documentsIn = (store, path) =>
    Object.keys(store).filter((documentPath) =>
      documentPath.slice(0, documentPath.lastIndexOf("/")) === path);
  // Which failure a batch's commit asks for, by the first document it deletes.
  const commitFailure = (path) => {
    if (path.startsWith("friendships/")) return "friendships-commit";
    if (path.startsWith("chats/")) return "chat-messages-commit";
    if (path.startsWith("users/")) return "hidden-commit";
    return "commit";
  };
  const db = {
    doc(path) {
      return { path, parent: { parent: { path: path.split("/").slice(0, -2).join("/") } } };
    },
    collection(path) {
      calls.push(`collection:${path}`);
      return {
        doc(id) {
          return { path: `${path}/${id}` };
        },
        // The query `friendships` or `chats` `where uids array-contains <uid>`, with a limit.
        where(field, op, value) {
          assert.ok(path === "friendships" || path === "chats", path);
          assert.equal(field, "uids");
          assert.equal(op, "array-contains");
          // As in Firestore, limit returns a new query, which returns at most `size` documents.
          const query = (size = Infinity) => ({
            limit(newSize) {
              assert.equal(newSize, 500);
              return query(newSize);
            },
            async get() {
              calls.push(`query:${path} ${field} ${op} ${value}`);
              failIfAsked(`${path}-query`);
              const docs = (path === "chats"
                ? Object.entries(state.chats)
                  .filter(([, chat]) => chat.uids.includes(value))
                  .map(([chatPath]) => ({ ref: chatRef(chatPath) }))
                : Object.entries(state.friendships)
                  .filter(([, uids]) => uids.includes(value))
                  .map(([friendshipPath]) => ({ ref: { path: friendshipPath } }))).slice(0, size);
              return { empty: docs.length === 0, docs };
            },
          });
          return query();
        },
        // A chat's messages or a student's delete-for-me markers, with a limit.
        limit(size) {
          assert.equal(size, 500);
          return {
            async get() {
              calls.push(`list:${path}`);
              const store = path.startsWith("chats/") ? state.messages : state.hidden;
              const docs = documentsIn(store, path)
                .slice(0, size)
                .map((documentPath) => ({ ref: { path: documentPath } }));
              return { empty: docs.length === 0, docs };
            },
          };
        },
      };
    },
    // The collection-group query `attendees where uid == <uid>`, with a limit, and batched deletes.
    collectionGroup(group) {
      if (group === "messages") {
        return { where(field, op, value) {
          assert.equal(field, "senderId"); assert.equal(op, "=="); assert.equal(value, UID);
          const query = { limit(size) { assert.equal(size, 500); return query; }, async get() {
            calls.push("query:messages"); failIfAsked("messages-query");
            const docs = Object.entries(state.messages).filter(([, data]) => data.senderId === value)
              .slice(0, 500).map(([path]) => ({ref: db.doc(path)}));
            return {docs, empty: docs.length === 0};
          }};
          return query;
        }};
      }
      if (group === "groups") {
      return {
        where(field, operator, uid) {
          assert.equal(field, "memberUids");
          assert.equal(operator, "array-contains");
          assert.equal(uid, UID);
          return {
            async get() {
              calls.push("query:memberships");
              failIfAsked("groups-query");
              return { docs: Object.entries(state.groups)
                .filter(([, data]) => data.memberUids.includes(uid))
                .map(([path]) => ({ ref: db.doc(path) })) };
            },
          };
        },
      };

      }
      calls.push(`collectionGroup:${group}`);
      return {
        where(field, op, value) {
          const query = {
            limit: () => query,
            async get() {
              calls.push(`query:${group} ${field} ${op} ${value}`);
              failIfAsked("query");
              const docs = [...state.registrations]
                .filter((path) => path.split("/")[3] === value)
                .map((path) => ({ ref: db.doc(path) }));
              return { empty: docs.length === 0, docs };
            },
          };
          return query;
        },
      };
    },
    batch() {
      const deletes = [];
      const updates = [];
      return {
        delete: (ref) => deletes.push(ref.path),
        update: (ref, data) => updates.push([ref.path, data]),
        async commit() {
          if (updates.length) {
            calls.push(`anonymise:${updates.length}`); failIfAsked("messages-write");
            beforeAnonymise(state);
            // As in Firestore, an update of a missing document fails the whole batch: NOT_FOUND.
            if (updates.some(([path]) => !state.messages[path])) throw backendError(5);
            for (const [path, data] of updates) Object.assign(state.messages[path], data);
            return;
          }
          calls.push(`commit:${deletes.join(",")}`);
          failIfAsked(commitFailure(deletes[0]));
          for (const path of deletes) {
            state.registrations.delete(path);
            delete state.friendships[path];
            delete state.messages[path];
            delete state.hidden[path];
          }
        },
      };
    },
    async runTransaction(updateFunction) {
      const writes = [];
      const result = await updateFunction({
        // A registration and its event, read together by the registration deletion (#405).
        async getAll(...refs) {
          calls.push(`getAll:${refs.map((ref) => ref.path).join(",")}`);
          failIfAsked("get");
          return refs.map(({ path }) => {
            const data = path.includes("/attendees/")
              ? (state.registrations.has(path) ? {} : null)
              : state.events[path] ?? null;
            return { exists: data !== null, get: (field) => data?.[field] };
          });
        },
        async get(ref) {
          calls.push(`get:${ref.path}`);
          failIfAsked("get");
          if (ref.path.includes("/groups/")) beforeGroupRead(state, ref.path);
          if (ref.path.startsWith("chats/")) beforeChatRead(state, ref.path);
          let data = state.groups[ref.path] ?? null;
          if (ref.path.startsWith("users/")) data = state.profile;
          if (ref.path.startsWith("chats/")) data = state.chats[ref.path] ?? null;
          if (ref.path.startsWith("deletedAccounts/")) {
            data = state.deletedUids.has(ref.path.split("/")[1]) ? {} : null;
          }
          return {
            exists: data !== null,
            get: (field) => (data === null ? undefined : data[field]),
          };
        },
        set(ref, data) {
          writes.push({ kind: "set", ref, data });
        },
        delete(ref) {
          writes.push({ kind: "delete", ref });
        },
        update(ref, data) {
          writes.push({ kind: "update", ref, data });
        },
      });
      for (const write of writes) {
        calls.push(`${write.kind}:${write.ref.path}`);
        // A registration's transaction fails at its commit, as the batch it replaces did.
        const registration =
            write.ref.path.includes("/attendees/") || /^events\/[^/]+$/.test(write.ref.path);
        failIfAsked(registration ? "commit" : write.kind);
        if (write.ref.path.startsWith("events/") && !registration) failIfAsked("group-write");
        if (write.ref.path.startsWith("chats/")) failIfAsked("chat-write");
      }
      for (const { kind, ref, data } of writes) {
        if (kind === "set" && ref.path.startsWith("deletedAccounts/")) state.deletionMarker = data;
        if (kind === "delete" && ref.path.startsWith("users/")) state.profile = null;
        if (kind === "delete" && ref.path.startsWith("publicProfiles/")) state.publicProfile = null;
        if (kind === "delete" && ref.path.includes("/attendees/")) {
          state.registrations.delete(ref.path);
        }
        if (kind === "update" && /^events\/[^/]+$/.test(ref.path)) {
          Object.assign(state.events[ref.path], data);
        } else if (kind === "update" && ref.path.startsWith("chats/")) {
          Object.assign(state.chats[ref.path], data);
        } else if (kind === "update") Object.assign(state.groups[ref.path], data);
        if (kind === "delete" && ref.path.includes("/groups/")) delete state.groups[ref.path];
        if (kind === "delete" && ref.path.includes("/groupMembers/")) delete state.markers[ref.path];
      }
      return result;
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
    const backend = fakeBackend({
      profile: { accountType: "association", isAssociationVerified },
      publicProfile: null,
    });
    await assertHttpsError(call(backend), "failed-precondition", DETAILS.ASSOCIATION);
    assert.deepEqual(backend.calls, ["collection:users", `get:users/${UID}`]);
    assert.notEqual(backend.state.profile, null);
    assert.equal(backend.state.deletionMarker, null);
    assert.equal(backend.state.authUserExists, true);
  }
});

test("profiles deleted and marked deleted, then registrations, friendships and chats, then Auth", async () => {
  const backend = fakeBackend();
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.calls, [
    "collection:users",
    `get:users/${UID}`,
    "collection:deletedAccounts",
    "collection:publicProfiles",
    `set:deletedAccounts/${UID}`,
    `delete:users/${UID}`,
    `delete:publicProfiles/${UID}`,
    ...REGISTRATION_CALLS,
    "collection:friendships",
    ...FRIENDSHIP_CALLS,
    ...CHAT_CALLS,
    "query:messages",
    "query:memberships",
    `deleteUser:${UID}`,
  ]);
  assert.equal(backend.state.profile, null);
  assert.equal(backend.state.publicProfile, null);
  assert.equal(backend.state.authUserExists, false);
});

test("only the student's registrations are deleted, other students' are kept", async () => {
  const backend = fakeBackend();
  await call(backend);
  assert.deepEqual([...backend.state.registrations], [OTHER_REGISTRATION]);
});

test("each event the student was going to counts one student less (#405)", async () => {
  const backend = fakeBackend();
  await call(backend);
  assert.deepEqual(backend.state.events, {
    "events/e1": { attendeeCount: 1 },
    "events/e2": { attendeeCount: 0 },
  });
});

test("a student without registrations is deleted too", async () => {
  const backend = fakeBackend({ registrations: [OTHER_REGISTRATION] });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.ok(!backend.calls.some((c) => c.startsWith("getAll:")));
  assert.equal(backend.state.authUserExists, false);
});

// The marker is written in the profile transaction, before this step, so the Security Rules refuse
// a new request from the deleted user's still-valid token: none can be left behind (#357).
test("the student's friendships are deleted, sent, received or accepted; others' are kept", async () => {
  const backend = fakeBackend();
  await call(backend);
  assert.deepEqual(Object.keys(backend.state.friendships), [OTHER_FRIENDSHIP]);
  const steps = backend.calls.filter((c) => c.startsWith("set:deletedAccounts") || c.startsWith("query:friendships") || c.startsWith("deleteUser"));
  assert.deepEqual(steps, [`set:deletedAccounts/${UID}`, FRIENDSHIP_CALLS[0], FRIENDSHIP_CALLS[2], `deleteUser:${UID}`]);
});

test("a student without friendships is deleted too", async () => {
  const backend = fakeBackend({ friendships: { [OTHER_FRIENDSHIP]: ["student-uid-2", "student-uid-3"] } });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.ok(!backend.calls.some((c) => c.startsWith("commit:friendships/")));
  assert.deepEqual(Object.keys(backend.state.friendships), [OTHER_FRIENDSHIP]);
  assert.equal(backend.state.authUserExists, false);
});

test("a failed friendship deletion keeps the Auth user, and a retry finishes", async () => {
  for (const operation of ["friendships-query", "friendships-commit"]) {
    const backend = fakeBackend();
    backend.fail[operation] = { error: backendError(14), once: true };
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.equal(Object.keys(backend.state.friendships).length, 3);
    assert.equal(backend.state.authUserExists, true);
    assert.deepEqual(backend.logs, [
      ["error", "deleteAccount step failed", { step: "delete-friendships", code: 14 }],
    ]);

    assert.deepEqual(await call(backend), { deleted: true });
    assert.deepEqual(Object.keys(backend.state.friendships), [OTHER_FRIENDSHIP]);
    assert.equal(backend.state.authUserExists, false);
  }
});

// The marker is written first, so the Security Rules refuse a new registration from the deleted
// user's still-valid token before the query runs: none can be left behind.
test("a failed registration deletion keeps the Auth user, and a retry finishes", async () => {
  for (const operation of ["query", "commit"]) {
    const backend = fakeBackend();
    backend.fail[operation] = { error: backendError(14), once: true };
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.equal(backend.state.profile, null);
    assert.deepEqual(backend.state.deletionMarker, {});
    assert.equal(backend.state.registrations.size, 3);
    assert.equal(backend.state.authUserExists, true);
    assert.ok(!backend.calls.some((c) => c.startsWith("deleteUser")));

    assert.deepEqual(await call(backend), { deleted: true });
    assert.deepEqual([...backend.state.registrations], [OTHER_REGISTRATION]);
    assert.equal(backend.state.authUserExists, false);
    // The retry lowered each counter once.
    assert.deepEqual(backend.state.events, {
      "events/e1": { attendeeCount: 1 },
      "events/e2": { attendeeCount: 0 },
    });
  }
});

// The marker is what the Security Rules check to refuse recreating the profiles with the deleted
// user's still-valid ID token, so it holds no personal data: only its document ID, the UID.
test("the deletion marker holds nothing but its UID as document ID", async () => {
  const backend = fakeBackend();
  await call(backend);
  assert.deepEqual(backend.state.deletionMarker, {});
});

test("the marker and the profile deletions are one transaction: all or nothing", async () => {
  const markerFails = fakeBackend();
  markerFails.fail.set = { error: backendError(14) };
  await assertHttpsError(call(markerFails), "unavailable", undefined);
  assert.notEqual(markerFails.state.profile, null);
  assert.notEqual(markerFails.state.publicProfile, null);
  assert.equal(markerFails.state.authUserExists, true);

  const deleteFails = fakeBackend();
  deleteFails.fail.delete = { error: backendError(14) };
  await assertHttpsError(call(deleteFails), "unavailable", undefined);
  assert.equal(deleteFails.state.deletionMarker, null);
});

test("a student without a profile is marked deleted too", async () => {
  const backend = fakeBackend({ profile: null, publicProfile: null });
  await call(backend);
  assert.deepEqual(backend.state.deletionMarker, {});
});

test("a student without a public profile (saved before #229) is deleted too", async () => {
  const backend = fakeBackend({ publicProfile: null });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(backend.state.profile, null);
  assert.equal(backend.state.authUserExists, false);
});

test("a profile without accountType is a student's and is deleted", async () => {
  const backend = fakeBackend({ profile: { displayName: "Student One" } });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(backend.state.profile, null);
  assert.equal(backend.state.publicProfile, null);
  assert.equal(backend.state.authUserExists, false);
});

test("an account without a profile (never verified) can delete itself", async () => {
  const backend = fakeBackend({ profile: null });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(backend.state.authUserExists, false);
});

test("only the user's profiles, marker, registrations, friendships, chats and markers are touched, and events' counters", async () => {
  const backend = fakeBackend();
  await call(backend);
  const collections = backend.calls.filter((c) => c.startsWith("collection"));
  assert.deepEqual(collections, [
    "collection:users",
    "collection:deletedAccounts",
    "collection:publicProfiles",
    "collectionGroup:attendees",
    "collection:friendships",
    "collection:chats",
    `collection:users/${UID}/hiddenChats`,
    `collection:users/${UID}/hiddenGroupChats`,
  ]);
  // Events are read and written only by the registrations' transactions, for their attendeeCount.
  const eventCalls = backend.calls.filter((c) => c.includes("events"));
  assert.deepEqual(eventCalls, REGISTRATION_CALLS.slice(2, 8));
});

test("account deletion leaves other members and removes empty groups and own markers", async () => {
  const shared = "events/shared/groups/g1";
  const solo = "events/solo/groups/g2";
  const unrelated = "events/other/groups/g3";
  const backend = fakeBackend({
    groups: {
      [shared]: { memberUids: [UID, "other-student"], createdBy: UID, capacity: 4 },
      [solo]: { memberUids: [UID], createdBy: UID, capacity: 4 },
      [unrelated]: { memberUids: ["other-student"], capacity: 4 },
    },
    markers: {
      [`events/shared/groupMembers/${UID}`]: { groupId: "g1" },
      [`events/solo/groupMembers/${UID}`]: { groupId: "g2" },
      "events/shared/groupMembers/other-student": { groupId: "g1" },
    },
  });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.state.groups[shared], {
    memberUids: ["other-student"], createdBy: UID, capacity: 4,
  });
  assert.equal(backend.state.groups[solo], undefined);
  assert.deepEqual(backend.state.groups[unrelated], { memberUids: ["other-student"], capacity: 4 });
  assert.deepEqual(backend.state.markers, { "events/shared/groupMembers/other-student": { groupId: "g1" } });
  assert.equal(backend.state.authUserExists, false);
  assert.deepEqual(await call(backend), { deleted: true });
});

test("membership query or transaction failure keeps Auth and a retry completes cleanup", async () => {
  for (const operation of ["groups-query", "group-write"]) {
    const path = "events/shared/groups/g1";
    const marker = `events/shared/groupMembers/${UID}`;
    const backend = fakeBackend({ groups: { [path]: { memberUids: [UID] } }, markers: { [marker]: { groupId: "g1" } } });
    backend.fail[operation] = { error: backendError(14), once: true };
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.equal(backend.state.authUserExists, true);
    assert.deepEqual(backend.state.groups[path].memberUids, [UID]);
    assert.notEqual(backend.state.markers[marker], undefined);
    assert.deepEqual(backend.logs, [["error", "deleteAccount step failed", { step: "delete-group-memberships", code: 14 }]]);
    assert.deepEqual(await call(backend), { deleted: true });
    assert.deepEqual(backend.state.groups, {});
    assert.deepEqual(backend.state.markers, {});
    assert.equal(backend.state.authUserExists, false);
  }
});

test("cleanup rereads a concurrently changed or removed group and removes its old marker", async () => {
  const path = "events/shared/groups/g1";
  const marker = `events/shared/groupMembers/${UID}`;
  for (const removed of [false, true]) {
    const backend = fakeBackend({
      groups: { [path]: { memberUids: [UID, "other-student"] } },
      markers: { [marker]: { groupId: "g1" } },
      beforeGroupRead(state, groupPath) {
        if (removed) delete state.groups[groupPath];
        else state.groups[groupPath].memberUids = ["other-student", "new-member"];
      },
    });
    assert.deepEqual(await call(backend), { deleted: true });
    assert.equal(backend.state.markers[marker], undefined);
    if (removed) assert.equal(backend.state.groups[path], undefined);
    else assert.deepEqual(backend.state.groups[path].memberUids, ["other-student", "new-member"]);
  }
});

test("an unexpected group path fails without deleting Auth", async () => {
  for (const groups of [
    { "groups/legacy": { memberUids: [UID] } },
  ]) {
    const backend = fakeBackend({ groups });
    await assertHttpsError(call(backend), "internal", undefined);
    assert.equal(backend.state.authUserExists, true);
    assert.equal(backend.logs[0][2].step, "delete-group-memberships");
    assert.ok(!JSON.stringify(backend.logs).includes(UID));
  }
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

  const registrationsFailed = fakeBackend();
  registrationsFailed.fail.commit = { error: backendError(4) };
  await assertHttpsError(call(registrationsFailed), "unavailable", undefined);
  assert.deepEqual(registrationsFailed.logs, [
    ["error", "deleteAccount step failed", { step: "delete-registrations", code: 4 }],
  ]);

  const noCode = fakeBackend();
  noCode.fail.get = { error: new TypeError(`unexpected for ${UID}`) };
  await assertHttpsError(call(noCode), "internal", undefined);
  assert.deepEqual(noCode.logs, [
    ["error", "deleteAccount step failed", { step: "delete-profile", code: null }],
  ]);
  for (const backend of [profileFailed, authFailed, registrationsFailed, noCode]) {
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


test("messages across current, former and deleted groups lose only their account link", async () => {
  const paths = ["events/e1/groups/shared/messages/m1", "events/left/groups/old/messages/m2",
    "events/gone/groups/gone/messages/m3"];
  const own = {senderId: UID, text: "Meet by the door", createdAt: 123};
  const other = {senderId: "other-student", text: "Coming", createdAt: 124};
  const backend = fakeBackend({messages: Object.fromEntries([
    ...paths.map(path => [path, {...own}]), ["events/e1/groups/shared/messages/other", other],
  ])});
  assert.deepEqual(await call(backend), {deleted: true});
  for (const path of paths) assert.deepEqual(backend.state.messages[path], {...own, senderId: ""});
  assert.deepEqual(backend.state.messages["events/e1/groups/shared/messages/other"], other);
  assert.equal(Object.values(backend.state.messages).some(m => m.senderId === UID), false);
  assert.deepEqual(await call(backend), {deleted: true});
});

test("anonymisation spans batches without dropping messages", async () => {
  const messages = Object.fromEntries(Array.from({length: 501}, (_, i) =>
    [`events/e/groups/g/messages/m${i}`, {senderId: UID, text: "Plan", createdAt: i}]));
  const backend = fakeBackend({messages});
  await call(backend);
  assert.equal(Object.keys(backend.state.messages).length, 501);
  assert.ok(Object.values(backend.state.messages).every(m => m.senderId === ""));
  assert.deepEqual(backend.calls.filter(c => c.startsWith("anonymise:")), ["anonymise:500", "anonymise:1"]);
});

test("a failed message query or write preserves Auth and retry completes anonymisation", async () => {
  for (const operation of ["messages-query", "messages-write"]) {
    const path = "events/e/groups/g/messages/m";
    const backend = fakeBackend({messages: {[path]: {senderId: UID, text: "Plan", createdAt: 1}}});
    backend.fail[operation] = {error: backendError(14), once: true};
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.equal(backend.state.authUserExists, true);
    assert.equal(backend.state.messages[path].senderId, UID);
    assert.deepEqual(backend.logs, [["error", "deleteAccount step failed", {step: "anonymise-messages", code: 14}]]);
    await call(backend);
    assert.equal(backend.state.authUserExists, false);
    assert.equal(backend.state.messages[path].senderId, "");
  }
});

test("malformed group lists do not block erasure or erase other entries", async () => {
  const path = "events/e/groups/g";
  for (const members of [[UID, 42, "other-student"], null, "malformed"]) {
    const backend = fakeBackend({groups: {[path]: {memberUids: [UID, "other-student"]}},
      markers: {[`events/e/groupMembers/${UID}`]: {groupId: "g"}},
      beforeGroupRead(state) {state.groups[path].memberUids = members;}});
    assert.deepEqual(await call(backend), {deleted: true});
    assert.deepEqual(backend.state.groups[path].memberUids, Array.isArray(members) ? [42, "other-student"] : members);
    assert.deepEqual(backend.state.markers, {});
    assert.equal(backend.state.authUserExists, false);
    assert.deepEqual(backend.logs, [["warn", "deleteAccount malformed group members", {code: "invalid-members"}]]);
  }
});

// One-to-one chats (#429, Architecture Diagram 3.8): the step after the friendships.
const FRIEND = "student-uid-2";
const GONE = "student-uid-3";

/** The path of the one-to-one chat of `a` and `b`, under their sorted pair ID (#338). */
function chatOf(a, b) {
  return `chats/${[a, b].sort().join("~")}`;
}

/** Messages of `chat` by ID, from `senders` (ID → senderId), with a fictional text and time. */
function chatMessages(chat, senders) {
  return Object.fromEntries(Object.entries(senders).map(([id, senderId]) =>
    [`${chat}/messages/${id}`, { senderId, text: `Text of ${id}`, createdAt: 1 }]));
}

/** The chats a retry's query would find for the student: those whose `uids` still list them. */
function chatsListing(backend, uid) {
  return Object.keys(backend.state.chats).filter((path) => backend.state.chats[path].uids.includes(uid));
}

/** The number of documents each batch deleting under `prefix` deleted, in order. */
function batchSizes(backend, prefix) {
  return backend.calls.filter((c) => c.startsWith(`commit:${prefix}`)).map((c) => c.split(",").length);
}

test("the other student keeps the chat, without the deleted UID (#429)", async () => {
  const chat = chatOf(UID, FRIEND);
  const backend = fakeBackend({
    chats: { [chat]: { uids: [UID, FRIEND] } },
    messages: chatMessages(chat, { a: UID, b: FRIEND }),
  });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.state.chats, { [chat]: { uids: [FRIEND] } });
  // The messages stay, the deleted account's without their author link (#241's step).
  assert.deepEqual(backend.state.messages, {
    [`${chat}/messages/a`]: { senderId: "", text: "Text of a", createdAt: 1 },
    [`${chat}/messages/b`]: { senderId: FRIEND, text: "Text of b", createdAt: 1 },
  });
  // After the friendships, one transaction reads the chat and the friend's deletion marker, then
  // writes uids; the messages step and the Auth user come after.
  const steps = backend.calls.filter((c) =>
    /^(query:friendships|query:chats|get:chats|get:deletedAccounts|update:chats|query:messages|deleteUser)/.test(c));
  assert.deepEqual(steps, [
    FRIENDSHIP_CALLS[0],
    FRIENDSHIP_CALLS[2],
    `query:chats uids array-contains ${UID}`,
    `get:${chat}`,
    `get:deletedAccounts/${FRIEND}`,
    `update:${chat}`,
    "query:messages",
    "query:messages",
    `deleteUser:${UID}`,
  ]);
});

test("several chats are each decided on their own, other pairs' chats untouched", async () => {
  const kept = chatOf(UID, FRIEND);
  const keptToo = chatOf(UID, "student-uid-0");
  const nobodyLeft = chatOf(UID, GONE);
  const othersChat = chatOf(FRIEND, GONE);
  const backend = fakeBackend({
    chats: {
      [kept]: { uids: [UID, FRIEND] },
      [keptToo]: { uids: ["student-uid-0", UID] },
      [nobodyLeft]: { uids: [UID, GONE] },
      [othersChat]: { uids: [FRIEND, GONE] },
    },
    deletedUids: [GONE],
    messages: {
      ...chatMessages(kept, { a: UID }),
      ...chatMessages(keptToo, { b: "student-uid-0" }),
      ...chatMessages(nobodyLeft, { c: UID, d: GONE }),
      ...chatMessages(othersChat, { e: FRIEND }),
    },
  });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.state.chats, {
    [kept]: { uids: [FRIEND] },
    [keptToo]: { uids: ["student-uid-0"] },
    [othersChat]: { uids: [FRIEND, GONE] },
  });
  assert.deepEqual(Object.keys(backend.state.messages), [
    `${kept}/messages/a`, `${keptToo}/messages/b`, `${othersChat}/messages/e`,
  ]);
  assert.equal(backend.state.authUserExists, false);
});

test("a chat already cleaned by an earlier attempt is left as it is", async () => {
  // An earlier attempt took the UID out of uids, then failed before Auth.
  const chat = chatOf(UID, FRIEND);
  const messages = chatMessages(chat, { a: "", b: FRIEND });
  const backend = fakeBackend({ chats: { [chat]: { uids: [FRIEND] } }, messages });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.state.chats, { [chat]: { uids: [FRIEND] } });
  assert.deepEqual(backend.state.messages, chatMessages(chat, { a: "", b: FRIEND }));
  assert.ok(!backend.calls.some((c) => c.includes(`:${chat}`)), "the chat is neither read nor written");
});

test("a chat gone before its transaction reads it is skipped", async () => {
  // The other student's deletion deleted it between this step's query and its transaction.
  const chat = chatOf(UID, FRIEND);
  const backend = fakeBackend({
    chats: { [chat]: { uids: [UID, FRIEND] } },
    beforeChatRead(state, path) { delete state.chats[path]; },
  });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.state.chats, {});
  assert.ok(!backend.calls.some((c) => c.startsWith("update:chats") || c.startsWith("delete:chats")));
  assert.equal(backend.state.authUserExists, false);
});

test("once both accounts are deleted, the chat's messages are deleted, then the chat", async () => {
  // The friend deleted their account first: their own step took their UID out of uids.
  const chat = chatOf(UID, FRIEND);
  const backend = fakeBackend({
    chats: { [chat]: { uids: [UID] } },
    deletedUids: [FRIEND],
    messages: chatMessages(chat, { a: "", b: UID }),
  });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.state.chats, {});
  assert.deepEqual(backend.state.messages, {});
  // uids is never written: the chat leaves the query only once it is gone.
  const writes = backend.calls.filter((c) => c.includes(chat) && /^(commit|update|delete):/.test(c));
  assert.deepEqual(writes, [`commit:${chat}/messages/a,${chat}/messages/b`, `delete:${chat}`]);
});

test("a chat whose other student's deletion stopped before this step is deleted too", async () => {
  // The friend's deletion wrote its marker, then stopped: their UID is still in uids, their
  // messages still name them, and it may never be retried.
  const chat = chatOf(UID, FRIEND);
  const backend = fakeBackend({
    chats: { [chat]: { uids: [UID, FRIEND] } },
    deletedUids: [FRIEND],
    messages: chatMessages(chat, { a: UID, b: FRIEND }),
  });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.state.chats, {});
  assert.deepEqual(backend.state.messages, {});
  assert.ok(!backend.calls.some((c) => c.startsWith("update:chats")));
});

test("a failure while deleting a chat keeps it listing the UID, and a retry finishes", async () => {
  // Between the messages and the chat, and after the first batch of messages.
  for (const operation of ["chat-delete", "chat-messages-commit"]) {
    const chat = chatOf(UID, FRIEND);
    const senders = Object.fromEntries(Array.from({ length: 501 }, (_, i) => [`m${i}`, UID]));
    const backend = fakeBackend({
      chats: { [chat]: { uids: [UID, FRIEND] } },
      deletedUids: [FRIEND],
      messages: chatMessages(chat, senders),
    });
    backend.fail[operation] = { error: backendError(14), once: true, after: operation === "chat-delete" ? 0 : 1 };
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.deepEqual(backend.logs, [["error", "deleteAccount step failed", { step: "leave-chats", code: 14 }]]);
    assert.equal(backend.state.authUserExists, true);
    // uids unchanged, so the retry's query still finds the chat.
    assert.deepEqual(backend.state.chats, { [chat]: { uids: [UID, FRIEND] } });
    assert.deepEqual(chatsListing(backend, UID), [chat]);
    assert.equal(Object.keys(backend.state.messages).length, operation === "chat-delete" ? 0 : 1);

    assert.deepEqual(await call(backend), { deleted: true });
    assert.deepEqual(backend.state.chats, {});
    assert.deepEqual(backend.state.messages, {});
    // The failed second batch is tried again by the retry.
    assert.deepEqual(batchSizes(backend, `${chat}/messages/`), operation === "chat-delete" ? [500, 1] : [500, 1, 1]);
    assert.equal(backend.state.authUserExists, false);
  }
});

test("a failed chats query or uids write keeps the Auth user, and a retry finishes", async () => {
  for (const operation of ["chats-query", "chat-write"]) {
    const chat = chatOf(UID, FRIEND);
    const backend = fakeBackend({ chats: { [chat]: { uids: [UID, FRIEND] } } });
    backend.fail[operation] = { error: backendError(14), once: true };
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.deepEqual(backend.logs, [["error", "deleteAccount step failed", { step: "leave-chats", code: 14 }]]);
    assert.deepEqual(backend.state.chats, { [chat]: { uids: [UID, FRIEND] } });
    assert.equal(backend.state.authUserExists, true);

    assert.deepEqual(await call(backend), { deleted: true });
    assert.deepEqual(backend.state.chats, { [chat]: { uids: [FRIEND] } });
    assert.equal(backend.state.authUserExists, false);
  }
});

/** 501 delete-for-me markers of each kind for the student, and two of the friend's. */
function manyMarkers() {
  const hidden = {
    [`users/${FRIEND}/hiddenChats/${UID}~${FRIEND}`]: { hiddenUpTo: 1 },
    [`users/${FRIEND}/hiddenGroupChats/e1~g1`]: { hiddenUpTo: 1 },
  };
  for (let i = 0; i < 501; i++) {
    hidden[`users/${UID}/hiddenChats/pair${i}`] = { hiddenUpTo: 1 };
    hidden[`users/${UID}/hiddenGroupChats/e${i}~g`] = { hiddenUpTo: 1 };
  }
  return hidden;
}

test("the student's delete-for-me markers of both kinds are deleted after the chats, others' kept", async () => {
  const chat = chatOf(UID, FRIEND);
  const backend = fakeBackend({ chats: { [chat]: { uids: [UID, FRIEND] } }, hidden: manyMarkers() });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(Object.keys(backend.state.hidden), [
    `users/${FRIEND}/hiddenChats/${UID}~${FRIEND}`, `users/${FRIEND}/hiddenGroupChats/e1~g1`,
  ]);
  assert.deepEqual(batchSizes(backend, `users/${UID}/hiddenChats/`), [500, 1]);
  assert.deepEqual(batchSizes(backend, `users/${UID}/hiddenGroupChats/`), [500, 1]);
  const order = backend.calls.filter((c) => c.startsWith(`update:${chat}`) || c.startsWith("commit:users/")
      || c.startsWith("query:messages"));
  assert.deepEqual(order.map((c) => c.split("/").slice(0, 3).join("/")), [
    `update:${chat}`,
    `commit:users/${UID}/hiddenChats`, `commit:users/${UID}/hiddenChats`,
    `commit:users/${UID}/hiddenGroupChats`, `commit:users/${UID}/hiddenGroupChats`,
    "query:messages",
  ]);
});

test("a failure after the first batch of markers keeps the Auth user, and a retry finishes", async () => {
  const backend = fakeBackend({ hidden: manyMarkers() });
  backend.fail["hidden-commit"] = { error: backendError(14), once: true, after: 1 };
  await assertHttpsError(call(backend), "unavailable", undefined);
  assert.deepEqual(backend.logs, [["error", "deleteAccount step failed", { step: "leave-chats", code: 14 }]]);
  assert.equal(backend.state.authUserExists, true);
  const left = (kind) => Object.keys(backend.state.hidden).filter((p) => p.startsWith(`users/${UID}/${kind}/`));
  assert.equal(left("hiddenChats").length, 1);
  assert.equal(left("hiddenGroupChats").length, 501);

  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(left("hiddenChats"), []);
  assert.deepEqual(left("hiddenGroupChats"), []);
  assert.equal(Object.keys(backend.state.hidden).length, 2);
  assert.equal(backend.state.authUserExists, false);
});

test("every chat of the student is found, more than a query page holds", async () => {
  // One read, without a limit: a limit would leave the chats after the first page listing the UID.
  const chats = {};
  for (let i = 0; i < 501; i++) {
    const friend = `student-uid-x${i}`;
    chats[chatOf(UID, friend)] = { uids: [UID, friend] };
  }
  const backend = fakeBackend({ chats });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.equal(Object.keys(backend.state.chats).length, 501);
  assert.deepEqual(chatsListing(backend, UID), []);
});

/**
 * Everything the deletion steps handle at once: the default registrations and friendships, a group
 * with its markers and a message, and one-to-one chats of every kind (kept, with an unsent message;
 * kept with the UID second in uids; nobody left, with more messages than a batch holds; another
 * pair's), with more delete-for-me markers of each kind than a batch holds, and the friend's.
 */
function everyKindOfData() {
  const kept = chatOf(UID, FRIEND);
  const keptToo = chatOf(UID, "student-uid-0");
  const nobodyLeft = chatOf(UID, GONE);
  const othersChat = chatOf(FRIEND, GONE);
  const both = Object.fromEntries(Array.from({ length: 501 }, (_, i) => [`m${i}`, i % 2 ? UID : GONE]));
  return {
    groups: { "events/e1/groups/g1": { memberUids: [UID, FRIEND] } },
    markers: {
      [`events/e1/groupMembers/${UID}`]: { groupId: "g1" },
      [`events/e1/groupMembers/${FRIEND}`]: { groupId: "g1" },
    },
    chats: {
      [kept]: { uids: [UID, FRIEND] },
      [keptToo]: { uids: ["student-uid-0", UID] },
      [nobodyLeft]: { uids: [UID, GONE] },
      [othersChat]: { uids: [FRIEND, GONE] },
    },
    deletedUids: [GONE],
    messages: {
      ...chatMessages(kept, { a: UID, b: FRIEND }),
      [`${kept}/messages/unsent`]: { senderId: UID, text: "", createdAt: 1 },
      ...chatMessages(keptToo, { c: UID }),
      ...chatMessages(nobodyLeft, both),
      ...chatMessages(othersChat, { d: GONE }),
      "events/e1/groups/g1/messages/g": { senderId: UID, text: "Text of g", createdAt: 1 },
    },
    hidden: manyMarkers(),
  };
}

/**
 * Makes the backend call at `index` of `backend.calls` fail once, as a lost connection does: the
 * fake records a call before it changes anything, so nothing of that call is applied.
 */
function failAtCall(backend, index) {
  backend.calls.push = function (entry) {
    const length = Array.prototype.push.call(this, entry);
    if (length - 1 === index) {
      delete this.push;
      throw backendError(14);
    }
    return length;
  };
}

test("a failure at any call, then a retry, ends as a deletion without failure", async () => {
  const clean = fakeBackend(everyKindOfData());
  assert.deepEqual(await call(clean), { deleted: true });
  // Every call that reaches Firestore or Auth; getting a collection's reference reaches neither.
  const reaching = clean.calls.flatMap((c, i) => (c.startsWith("collection") ? [] : [[c, i]]));
  assert.ok(reaching.some(([c]) => c.startsWith("commit:users/")), "the markers are deleted");
  for (const [operation, index] of reaching) {
    const backend = fakeBackend(everyKindOfData());
    failAtCall(backend, index);
    await assertHttpsError(call(backend), "unavailable", undefined);
    assert.equal(backend.state.authUserExists, true, operation);
    assert.equal(backend.logs.length, 1, operation);
    assert.equal(backend.logs[0][2].code, 14, operation);
    // A chat nobody else is left in still lists the UID until it is gone, so the retry finds it.
    for (const [path, { uids }] of Object.entries(backend.state.chats)) {
      const keptForSomeone = uids.some((other) => other !== UID && !backend.state.deletedUids.has(other));
      assert.ok(uids.includes(UID) || keptForSomeone, `${operation}: ${path}`);
    }

    assert.deepEqual(await call(backend), { deleted: true }, operation);
    assert.deepEqual(backend.state, clean.state, operation);
  }
  // What a deletion without failure leaves, as the steps' own tests check it.
  assert.deepEqual(clean.state.chats, {
    [chatOf(UID, FRIEND)]: { uids: [FRIEND] },
    [chatOf(UID, "student-uid-0")]: { uids: ["student-uid-0"] },
    [chatOf(FRIEND, GONE)]: { uids: [FRIEND, GONE] },
  });
  assert.ok(!Object.keys(clean.state.messages).some((path) => path.startsWith(chatOf(UID, GONE))));
  assert.ok(!Object.values(clean.state.messages).some((message) => message.senderId === UID));
  assert.deepEqual(clean.state.messages[`${chatOf(UID, FRIEND)}/messages/unsent`], { senderId: "", text: "", createdAt: 1 });
  assert.equal(Object.keys(clean.state.hidden).length, 2);
});

test("a message deleted while the messages step runs doesn't fail the deletion", async () => {
  // The friend's own deletion deletes a chat nobody is left in, between this step's query and its
  // batch: the batch fails with NOT_FOUND, and the step queries again.
  const chat = chatOf(UID, FRIEND);
  const group = "events/e/groups/g/messages/m";
  let deletedMeanwhile = false;
  const backend = fakeBackend({
    messages: { ...chatMessages(chat, { a: UID, b: UID }), [group]: { senderId: UID, text: "Plan", createdAt: 1 } },
    beforeAnonymise(state) {
      if (deletedMeanwhile) return;
      deletedMeanwhile = true;
      delete state.messages[`${chat}/messages/a`];
      delete state.messages[`${chat}/messages/b`];
    },
  });
  assert.deepEqual(await call(backend), { deleted: true });
  assert.deepEqual(backend.logs, []);
  assert.deepEqual(backend.state.messages, { [group]: { senderId: "", text: "Plan", createdAt: 1 } });
  assert.deepEqual(backend.calls.filter((c) => c.startsWith("anonymise:")), ["anonymise:3", "anonymise:1"]);
  assert.equal(backend.state.authUserExists, false);
});

test("a batch refused twice with the same messages is a failure, not an endless retry", async () => {
  // NOT_FOUND although no message was deleted: querying again would return the same batch forever.
  const path = "events/e/groups/g/messages/m";
  const backend = fakeBackend({ messages: { [path]: { senderId: UID, text: "Plan", createdAt: 1 } } });
  backend.fail["messages-write"] = { error: backendError(5) };
  await assertHttpsError(call(backend), "internal", undefined);
  assert.deepEqual(backend.calls.filter((c) => c.startsWith("anonymise:")), ["anonymise:1", "anonymise:1"]);
  assert.deepEqual(backend.logs, [["error", "deleteAccount step failed", { step: "anonymise-messages", code: 5 }]]);
  assert.equal(backend.state.messages[path].senderId, UID);
  assert.equal(backend.state.authUserExists, true);
});
