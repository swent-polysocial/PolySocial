// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #447 review under Lucas Pinto Ricardo’s supervision and approval).
"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const { fakeFirestore } = require("./testing/fakeFirestore");
const { syncFriendLists } = require("./syncFriendLists");

const pairId = "u1~u2";
const source = `friendships/${pairId}`;
const staleEntry = "publicProfiles/u1/friends/u2";

test("a new request or an already removed request needs no writes", async () => {
  const pending = fakeFirestore([source], {}, { [source]: { status: "pending" } });
  const missing = fakeFirestore([]);
  for (const { db, state } of [pending, missing]) {
    await syncFriendLists(db, pairId);
    assert.equal(state.commits, 0);
  }
});

test("a one-sided stale entry is removed and a duplicate delivery does no work", async () => {
  const { db, documents, state } = fakeFirestore([staleEntry]);
  await syncFriendLists(db, pairId);
  assert.equal(documents.has(staleEntry), false);
  assert.equal(state.commits, 1);

  await syncFriendLists(db, pairId);
  assert.equal(state.commits, 1);
});
