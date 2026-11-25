// Contributors: Claude (Opus 5.5, Anthropic) drafted the fake Firestore of the registrations'
// tests, #275 and #405; moved here and made to hold documents at any depth for the deleted
// groups' tests, #382, and other fields for the event's creator, after review.
// Contributors: Claude (Opus 5.5, Anthropic) (check-ins deleted with the account and the event,
// #472); Konstantinos Perdikeas (directed, reviewed and owns it).
"use strict";

// Fakes shared by the Cloud Functions' unit tests (no emulator). This folder isn't deployed
// (firebase.json's functions "ignore").
const assert = require("node:assert/strict");
const { MAX_BATCH_WRITES } = require("../registrations");

/** An error as the Admin SDK reports it, with a gRPC status code. */
function backendError(code) {
  return Object.assign(new Error("raw backend failure for event-1 and student-1"), { code });
}

/**
 * A fake Firestore holding documents as paths, at any depth (`events/{eventId}`,
 * `events/{eventId}/attendees/{uid}`, `events/{eventId}/groups/{groupId}/messages/{id}`…), with
 * each event's `attendeeCount` in `counts` (by path, absent when the event has none) and any other
 * field read in `fields` (by path, such as `{ createdBy: "organizer" }`). It answers a
 * collection's documents (its direct children only) and the collection-group queries on the
 * attendees' and the check-ins' `uid` (#472), with `limit`, and runs batches and transactions (reading a document, a query,
 * or several documents with `getAll`) whose deletes and updates apply together at commit.
 * `commits` counts the commits that wrote something; `failCommit` makes the next one throw;
 * `onCommit` runs after each commit, and `onTransaction` before each transaction's reads, to write
 * in between.
 */
function fakeFirestore(paths, counts = {}, fields = {}) {
  const documents = new Set(paths);
  const state = {
    commits: 0,
    failCommit: null,
    onCommit: null,
    onTransaction: null,
    counts: new Map(Object.entries(counts)),
    fields: new Map(Object.entries(fields)),
  };
  const depth = (path) => path.split("/").length;
  const docRef = (path) => ({
    path,
    get parent() {
      return { parent: docRef(path.split("/").slice(0, -2).join("/")) };
    },
    collection: (id) => collectionRef(`${path}/${id}`),
  });
  const snapshot = (path) => ({
    exists: documents.has(path),
    get: (field) =>
      field === "attendeeCount" ? state.counts.get(path) : state.fields.get(path)?.[field],
  });
  const uidOf = (path) => path.split("/")[3];
  const query = (matches, max = Infinity) => ({
    limit: (n) => query(matches, n),
    async get() {
      const docs = [...documents].filter(matches).slice(0, max).map((path) => ({ ref: docRef(path) }));
      return { empty: docs.length === 0, size: docs.length, docs };
    },
  });
  const collectionRef = (path) => ({
    ...query((child) => child.startsWith(`${path}/`) && depth(child) === depth(path) + 1),
    doc: (id) => docRef(`${path}/${id}`),
  });
  async function commit(deletes, updates = []) {
    assert.ok(deletes.length + updates.length <= MAX_BATCH_WRITES, "a commit holds 500 writes");
    if (state.failCommit) {
      const error = state.failCommit;
      state.failCommit = null;
      throw error;
    }
    for (const path of deletes) documents.delete(path);
    for (const [path, data] of updates) {
      assert.ok(documents.has(path), `update of a missing document ${path}`);
      state.counts.set(path, data.attendeeCount);
    }
    if (deletes.length + updates.length > 0) state.commits += 1;
    if (state.onCommit) state.onCommit(documents);
  }
  const db = {
    collection: collectionRef,
    doc: docRef,
    collectionGroup: (group) => ({
      where: (field, op, value) => {
        assert.ok(["attendees", "checkIns"].includes(group), `no query on ${group}`);
        assert.deepEqual([field, op], ["uid", "=="]);
        return query((path) => path.split("/")[2] === group && uidOf(path) === value);
      },
    }),
    batch() {
      const deletes = [];
      return {
        delete: (ref) => deletes.push(ref.path),
        commit: () => commit(deletes),
      };
    },
    async runTransaction(update) {
      if (state.onTransaction) state.onTransaction(documents);
      const deletes = [];
      const updates = [];
      const result = await update({
        // A query has limit() and is read with get(); a document reference is read by its path.
        get: async (target) => (target.limit ? target.get() : snapshot(target.path)),
        getAll: async (...targets) => targets.map((target) => snapshot(target.path)),
        delete: (ref) => deletes.push(ref.path),
        update: (ref, data) => updates.push([ref.path, data]),
      });
      await commit(deletes, updates);
      return result;
    },
  };
  return { db, documents, state };
}

/** A logger that keeps every entry as `[level, ...arguments]` in `logs`. */
function fakeLogger() {
  const logs = [];
  const logger = {};
  for (const level of ["debug", "log", "info", "warn", "error"]) {
    logger[level] = (...entry) => logs.push([level, ...entry]);
  }
  return { logger, logs };
}

module.exports = { backendError, fakeFirestore, fakeLogger };
