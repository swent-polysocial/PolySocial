// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged;
// Opus 5.5: the group marker deleted when its owner leaves the group, decided on 2026-11-16).
// PROTOTYPE for #365, never merged: unsend and delete for me in group chats, from
// docs/Architecture-Diagram.md section 3.8 (37 cases). The helpers are copied from
// chats.prototype.test.js. From the repository root (firebase/firestore/PROTOTYPE-365.md):
//   firebase emulators:exec --project demo-polysocial --only firestore "node firebase/firestore/chats.group.prototype.test.js"
const { after, before, beforeEach, test } = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  collection,
  collectionGroup,
  deleteDoc,
  doc,
  documentId,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  runTransaction,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
  Timestamp,
} = require("firebase/firestore");

const PROJECT_ID = "demo-polysocial";
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(process.env.RULES_FILE || path.join(__dirname, "firestore.rules"), "utf8"),
    },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await admin(async (db) => {
    for (const uid of ["u1", "u2", "u3"]) {
      await setDoc(doc(db, `users/${uid}`), { uid, displayName: `Student ${uid}` });
      await setDoc(doc(db, `publicProfiles/${uid}`), {
        displayName: `Student ${uid}`, section: "IN", year: "BA3", interests: [],
      });
    }
    await setDoc(doc(db, "users/club"), {
      uid: "club", accountType: "association", isAssociationVerified: false,
    });
    await setDoc(doc(db, "users/verifiedClub"), {
      uid: "verifiedClub", accountType: "association", isAssociationVerified: true,
    });
    await setDoc(doc(db, "users/epflClub"), {
      uid: "epflClub", accountType: "association", isAssociationVerified: true,
    });
  });
});

after(async () => {
  await env.cleanup();
});

// ---- helpers ----

async function admin(work) {
  let result;
  await env.withSecurityRulesDisabled(async (context) => {
    result = await work(context.firestore());
  });
  return result;
}

function firestoreAs(email, emailVerified, uid) {
  return env.authenticatedContext(uid, { email, email_verified: emailVerified }).firestore();
}

/** A verified @epfl.ch student signed in as [uid]. */
function studentDb(uid) {
  return firestoreAs("student@epfl.ch", true, uid);
}

/** A verified non-EPFL account (an association) signed in as [uid]. */
function associationDb(uid) {
  return firestoreAs("contact@club.example", true, uid);
}

/** The pair ID of #338: the two UIDs sorted and joined by "~". */
function pairIdOf(a, b) {
  return [a, b].sort().join("~");
}

function seedFriendship(a, b, status = "accepted", id = pairIdOf(a, b)) {
  return admin((db) =>
    setDoc(doc(db, `friendships/${id}`), {
      uids: [a, b].sort(),
      requestedBy: a,
      status,
      createdAt: Timestamp.fromMillis(1_790_000_000_000),
      ...(status === "accepted" ? { acceptedAt: Timestamp.fromMillis(1_790_000_001_000) } : {}),
    }),
  );
}

function seedChat(a, b, uids = [a, b].sort(), id = pairIdOf(a, b)) {
  return admin((db) => setDoc(doc(db, `chats/${id}`), { uids }));
}

function seedMessage(pairId, id, senderId, millis = 1_790_000_000_000) {
  return admin((db) =>
    setDoc(doc(db, `chats/${pairId}/messages/${id}`), {
      senderId, text: "Seeded", createdAt: Timestamp.fromMillis(millis),
    }),
  );
}

function seedDeletedAccount(uid) {
  return admin((db) => setDoc(doc(db, `deletedAccounts/${uid}`), {}));
}

async function stored(docPath) {
  return admin(async (db) => {
    const snapshot = await getDoc(doc(db, docPath));
    return snapshot.exists() ? snapshot.data() : null;
  });
}

async function expectMissing(docPath) {
  const data = await stored(docPath);
  if (data !== null) throw new Error(`Expected no ${docPath}, got ${JSON.stringify(data)}`);
}

function newMessage(uid, text = "See you at 6?") {
  return { senderId: uid, text, createdAt: serverTimestamp() };
}

/**
 * Sends as the app will: one transaction tried once that reads the chat and the message ID
 * (#238's retry check), creates the chat with the message when it is missing, and otherwise
 * writes only the message.
 */
function sendAs(db, pairId, message, id = "new") {
  return runTransaction(
    db,
    async (transaction) => {
      const chat = doc(db, `chats/${pairId}`);
      const storedMessage = doc(db, `chats/${pairId}/messages/${id}`);
      const snapshot = await transaction.get(chat);
      if ((await transaction.get(storedMessage)).exists()) return "already stored";
      if (!snapshot.exists()) transaction.set(chat, { uids: pairId.split("~") });
      transaction.set(storedMessage, message);
      return "sent";
    },
    { maxAttempts: 1 },
  );
}

/** Writes only the message, without reading or creating the chat. */
function sendOnlyMessageAs(db, pairId, message, id = "new") {
  return setDoc(doc(db, `chats/${pairId}/messages/${id}`), message);
}

/** The chat screen's query (#237's): every message, oldest first. */
function messagesOf(db, pairId) {
  return query(collection(db, `chats/${pairId}/messages`), orderBy("createdAt"));
}

/** The Chats tab's latest message of a chat (#363's limit(1)). */
function latestOf(db, pairId) {
  return query(collection(db, `chats/${pairId}/messages`), orderBy("createdAt", "desc"), limit(1));
}

/** The Chats tab's query: the student's own one-to-one chats. */
function myChats(db, uid) {
  return query(collection(db, "chats"), where("uids", "array-contains", uid));
}

function ids(snapshot) {
  return snapshot.docs.map((d) => d.id).sort().join(",");
}

function expectEqual(actual, expected, what) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${what}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

/** Friend request, as #338: created pending by [from], with the fixed fields. */
function requestAs(from, to) {
  return setDoc(doc(studentDb(from), `friendships/${pairIdOf(from, to)}`), {
    uids: [from, to].sort(),
    requestedBy: from,
    status: "pending",
    createdAt: serverTimestamp(),
  });
}

function acceptAs(uid, other) {
  return updateDoc(doc(studentDb(uid), `friendships/${pairIdOf(uid, other)}`), {
    status: "accepted",
    acceptedAt: serverTimestamp(),
  });
}

function removeFriendAs(uid, other) {
  return deleteDoc(doc(studentDb(uid), `friendships/${pairIdOf(uid, other)}`));
}

// ---- group chats (#365): helpers ----

const { deleteField } = require("firebase/firestore");

const T1 = 1_790_000_000_000;
const T2 = 1_790_000_060_000;
const T3 = 1_790_000_120_000;
const GROUP_CREATED = 1_789_999_000_000;
const FAR_FUTURE = Timestamp.fromMillis(4_102_444_800_000);

function groupPath(eventId, groupId) {
  return `events/${eventId}/groups/${groupId}`;
}

function groupMessagePath(eventId, groupId, id) {
  return `${groupPath(eventId, groupId)}/messages/${id}`;
}

/** A group with its members and their groupMembers markers, rules off, as #232 stores it. */
function seedGroup(eventId, groupId, members) {
  return admin(async (db) => {
    await setDoc(doc(db, groupPath(eventId, groupId)), {
      capacity: 4, memberUids: members, createdBy: members[0],
      createdAt: Timestamp.fromMillis(GROUP_CREATED),
    });
    for (const uid of members) {
      await setDoc(doc(db, `events/${eventId}/groupMembers/${uid}`), { groupId });
    }
  });
}

function seedGroupMessage(eventId, groupId, id, senderId, millis) {
  return admin((db) =>
    setDoc(doc(db, groupMessagePath(eventId, groupId, id)), {
      senderId, text: "Seeded", createdAt: Timestamp.fromMillis(millis),
    }),
  );
}

/** e1/g1 with members u1 and u2 and three messages; u3 is in another group of e1. */
async function seedGroupWithMessages() {
  await admin((db) => setDoc(doc(db, "events/e1"), {
    title: "Event", isPrivate: false, createdBy: "u1", organizerIds: ["u1"], allowedUids: ["u1"],
  }));
  await seedGroup("e1", "g1", ["u1", "u2"]);
  await seedGroup("e1", "g2", ["u3"]);
  await seedGroupMessage("e1", "g1", "m1", "u1", T1);
  await seedGroupMessage("e1", "g1", "m2", "u2", T2);
  await seedGroupMessage("e1", "g1", "m3", "u1", T3);
}

/** Unsends as the app will, like the one-to-one chat's: read first, then empty only the text. */
function unsendGroupAs(db, uid, eventId, groupId, id) {
  return runTransaction(
    db,
    async (transaction) => {
      const message = doc(db, groupMessagePath(eventId, groupId, id));
      const snapshot = await transaction.get(message);
      if (!snapshot.exists()) return "missing";
      if (snapshot.get("senderId") !== uid) return "not yours";
      if (snapshot.get("text") === "") return "already unsent";
      transaction.update(message, { text: "" });
      return "unsent";
    },
    { maxAttempts: 1 },
  );
}

function updateGroupMessageAs(db, eventId, groupId, id, changes) {
  return updateDoc(doc(db, groupMessagePath(eventId, groupId, id)), changes);
}

/** #237's chat query and #363's latest message. */
function groupChatOf(db, eventId, groupId) {
  return query(collection(db, `${groupPath(eventId, groupId)}/messages`), orderBy("createdAt"));
}

function groupLatestOf(db, eventId, groupId) {
  return query(
    collection(db, `${groupPath(eventId, groupId)}/messages`), orderBy("createdAt", "desc"), limit(1),
  );
}

/**
 * A student leaves a group as #232's leaveGroup does (it reads the group and their groupMembers
 * marker, writes memberUids without them or deletes the group when they were its last member, and
 * deletes the groupMembers marker if it exists), plus what the rejoin decision (2026-11-16) adds:
 * one delete of their group-chat marker, without reading it, whether or not it exists. With
 * `deleteMarker: false`, it leaves as today's leaveGroup does, or a client that skips that delete.
 */
function leaveGroupAs(
  db, uid, eventId, groupId,
  { deleteMarker = true, markerId = groupMarkerId(eventId, groupId) } = {},
) {
  return runTransaction(db, async (transaction) => {
    const group = doc(db, groupPath(eventId, groupId));
    const member = doc(db, `events/${eventId}/groupMembers/${uid}`);
    const snapshot = await transaction.get(group);
    const hasMember = (await transaction.get(member)).exists();
    const remaining = snapshot.get("memberUids").filter((other) => other !== uid);
    if (remaining.length === 0) transaction.delete(group);
    else transaction.update(group, { memberUids: remaining });
    if (hasMember) transaction.delete(member);
    // No marker ID (see groupMarkerId): its chat can't be hidden, and the delete would fail the leave.
    if (deleteMarker && markerId !== null) transaction.delete(doc(db, groupMarkerPath(uid, markerId)));
  });
}

/** A student joins a group as #232's joinGroup does: their UID appended, with their groupMembers marker. */
function joinGroupAs(db, uid, eventId, groupId) {
  return runTransaction(db, async (transaction) => {
    const group = doc(db, groupPath(eventId, groupId));
    const member = doc(db, `events/${eventId}/groupMembers/${uid}`);
    const snapshot = await transaction.get(group);
    if ((await transaction.get(member)).exists()) throw new Error(`${uid} is already in a group`);
    transaction.update(group, { memberUids: [...snapshot.get("memberUids"), uid] });
    transaction.set(member, { groupId });
  });
}

/**
 * The app's one pure function for a group marker's ID, `{eventId}~{groupId}`, or null when it can't
 * be built: either ID contains '~' (the rules refuse it), the joined ID is one Firestore reserves
 * (`__.*__`, e.g. `__a~b__` from two valid IDs) or it is over 1,500 bytes. Hide refuses on null;
 * leave skips the delete.
 */
function groupMarkerId(eventId, groupId) {
  if (eventId.includes("~") || groupId.includes("~")) return null;
  const id = `${eventId}~${groupId}`;
  if (/^__[\s\S]*__$/.test(id) || Buffer.byteLength(id, "utf8") > 1500) return null;
  return id;
}

function groupMarkerPath(uid, markerId) {
  return `users/${uid}/hiddenGroupChats/${markerId}`;
}

/** Delete for me, as the app will: read the stored marker, never write an earlier time. */
function hideGroupAs(db, uid, markerId, upTo) {
  return runTransaction(
    db,
    async (transaction) => {
      const marker = doc(db, groupMarkerPath(uid, markerId));
      const snapshot = await transaction.get(marker);
      if (snapshot.exists() && !isNewer(upTo, snapshot.get("hiddenUpTo"))) return "kept";
      transaction.set(marker, { hiddenUpTo: upTo });
      return "hidden";
    },
    { maxAttempts: 1 },
  );
}

function isNewer(a, b) {
  return a.seconds > b.seconds || (a.seconds === b.seconds && a.nanoseconds > b.nanoseconds);
}

/**
 * The Chats tab's rule for a group chat (#364 lists every group chat, with or without a message):
 * hidden while the student has a marker and the latest message, if any, is no newer than it.
 */
function isGroupChatListed(latest, marker) {
  if (!marker) return true;
  return latest !== null && isNewer(latest.createdAt, marker.hiddenUpTo);
}

async function groupLatestFor(db, eventId, groupId) {
  const snapshot = await assertSucceeds(getDocs(groupLatestOf(db, eventId, groupId)));
  return snapshot.empty ? null : snapshot.docs[0].data();
}

async function groupMarkerFor(db, uid, markerId) {
  const snapshot = await assertSucceeds(getDoc(doc(db, groupMarkerPath(uid, markerId))));
  return snapshot.exists() ? snapshot.data() : null;
}

// ---- unsend in a group chat ----

test("[group unsend] a member unsends their message: every member reads it in place, empty", async () => {
  await seedGroupWithMessages();
  expectEqual(await assertSucceeds(unsendGroupAs(studentDb("u1"), "u1", "e1", "g1", "m3")), "unsent", "unsend");
  const message = await stored(groupMessagePath("e1", "g1", "m3"));
  expectEqual(
    [Object.keys(message).sort(), message.senderId, message.text, message.createdAt.toMillis()],
    [["createdAt", "senderId", "text"], "u1", "", T3],
    "stored",
  );
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    const history = await assertSucceeds(getDocs(groupChatOf(db, "e1", "g1")));
    expectEqual(history.docs.map((d) => [d.id, d.get("text")]), [["m1", "Seeded"], ["m2", "Seeded"], ["m3", ""]], uid);
    const latest = await groupLatestFor(db, "e1", "g1");
    expectEqual([latest.text, latest.createdAt.toMillis()], ["", T3], `latest for ${uid}`);
  }
  expectEqual(await assertSucceeds(unsendGroupAs(studentDb("u1"), "u1", "e1", "g1", "m3")), "already unsent", "retry");
  await assertSucceeds(updateGroupMessageAs(studentDb("u1"), "e1", "g1", "m3", { text: "" }));
});

test("[group unsend] only the sender: another member, a student of another group and an outsider can't", async () => {
  await seedGroupWithMessages();
  for (const uid of ["u2", "u3"]) {
    await assertFails(updateGroupMessageAs(studentDb(uid), "e1", "g1", "m1", { text: "" }));
  }
  await assertFails(updateGroupMessageAs(studentDb("u1"), "e1", "g1", "m2", { text: "" }));
  expectEqual((await stored(groupMessagePath("e1", "g1", "m1"))).text, "Seeded", "text");
});

test("[group unsend] only the text, only to empty: no edit, no restore, no other field, no delete", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  for (const changes of [
    { text: "Edited" }, { text: " " }, { text: null }, { text: deleteField() },
    { text: "", senderId: "" }, { text: "", createdAt: serverTimestamp() }, { text: "", deletedAt: serverTimestamp() },
  ]) {
    await assertFails(updateGroupMessageAs(db, "e1", "g1", "m1", changes)).catch((error) => {
      throw new Error(`${JSON.stringify(Object.keys(changes))} was allowed: ${error.message}`);
    });
  }
  await assertSucceeds(unsendGroupAs(db, "u1", "e1", "g1", "m1"));
  await assertFails(updateGroupMessageAs(db, "e1", "g1", "m1", { text: "Seeded" }));
  await assertFails(deleteDoc(doc(db, groupMessagePath("e1", "g1", "m1"))));
  expectEqual((await stored(groupMessagePath("e1", "g1", "m1"))).text, "", "text");
});

test("[group unsend] a member who left the group can no longer unsend; the other members still can", async () => {
  await seedGroupWithMessages();
  await assertSucceeds(leaveGroupAs(studentDb("u1"), "u1", "e1", "g1"));
  await assertFails(updateGroupMessageAs(studentDb("u1"), "e1", "g1", "m1", { text: "" }));
  await assertSucceeds(updateGroupMessageAs(studentDb("u2"), "e1", "g1", "m2", { text: "" }));
});

test("[group unsend] a member removed from a private event keeps their group's chat, and unsends", async () => {
  await seedGroupWithMessages();
  await admin((db) => setDoc(doc(db, "events/e1"), {
    title: "Event", isPrivate: true, createdBy: "u3", organizerIds: ["u3"], allowedUids: ["u3"],
  }));
  await assertSucceeds(updateGroupMessageAs(studentDb("u1"), "e1", "g1", "m1", { text: "" }));
});

test("[group unsend] once the last member left and the group is deleted, nobody unsends", async () => {
  await seedGroup("e1", "solo", ["u1"]);
  await seedGroupMessage("e1", "solo", "m1", "u1", T1);
  await assertSucceeds(leaveGroupAs(studentDb("u1"), "u1", "e1", "solo"));
  await expectMissing(groupPath("e1", "solo"));
  await assertFails(updateGroupMessageAs(studentDb("u1"), "e1", "solo", "m1", { text: "" }));
});

test("[group unsend] under a deletion marker, a member can't unsend", async () => {
  await seedGroupWithMessages();
  await seedDeletedAccount("u1");
  await assertFails(updateGroupMessageAs(studentDb("u1"), "e1", "g1", "m1", { text: "" }));
});

test("[group unsend] a message whose sender was anonymised can't be unsent by anyone", async () => {
  await seedGroupWithMessages();
  await seedGroupMessage("e1", "g1", "anonymous", "", T1);
  for (const uid of ["u1", "u2"]) {
    await assertFails(updateGroupMessageAs(studentDb(uid), "e1", "g1", "anonymous", { text: "" }));
  }
});

for (const [name, db, uid] of [
  ["an @epfl.ch association listed as a member", () => studentDb("epflClub"), "epflClub"],
  ["a non-EPFL association listed as a member", () => associationDb("club"), "club"],
  ["an unverified @epfl.ch student", () => firestoreAs("student@epfl.ch", false, "u1"), "u1"],
  ["a signed-out user", () => env.unauthenticatedContext().firestore(), "u1"],
]) {
  test(`[group unsend] ${name} can't unsend`, async () => {
    await seedGroup("e1", "g1", [uid, "u2"]);
    await seedGroupMessage("e1", "g1", "m1", uid, T1);
    await assertFails(updateGroupMessageAs(db(), "e1", "g1", "m1", { text: "" }));
  });
}

test("[group unsend] no query across groups' messages, even for one's own", async () => {
  await seedGroupWithMessages();
  await assertFails(getDocs(query(collectionGroup(studentDb("u1"), "messages"), where("senderId", "==", "u1"))));
});

// ---- delete a group chat for me ----

test("[group hide] a member hides a group chat up to its latest message; the other members see no change", async () => {
  await seedGroupWithMessages();
  const mine = studentDb("u1");
  const id = groupMarkerId("e1", "g1");
  const latest = await groupLatestFor(mine, "e1", "g1");
  expectEqual(await assertSucceeds(hideGroupAs(mine, "u1", id, latest.createdAt)), "hidden", "hide");
  expectEqual(isGroupChatListed(latest, await groupMarkerFor(mine, "u1", id)), false, "for u1");
  expectEqual(ids(await assertSucceeds(getDocs(collection(mine, "users/u1/hiddenGroupChats")))), id, "markers");
  const other = studentDb("u2");
  await assertFails(getDoc(doc(other, groupMarkerPath("u1", id))));
  await assertFails(getDocs(collection(other, "users/u1/hiddenGroupChats")));
  expectEqual(isGroupChatListed(await groupLatestFor(other, "e1", "g1"), null), true, "for u2");
  // A message from another member brings it back; an unsend doesn't.
  await assertSucceeds(unsendGroupAs(mine, "u1", "e1", "g1", "m3"));
  expectEqual(isGroupChatListed(await groupLatestFor(mine, "e1", "g1"), await groupMarkerFor(mine, "u1", id)), false, "after unsend");
  await assertSucceeds(runTransaction(other, async (transaction) => {
    transaction.set(doc(other, groupMessagePath("e1", "g1", "m4")), newMessage("u2", "Still coming?"));
  }));
  expectEqual(isGroupChatListed(await groupLatestFor(mine, "e1", "g1"), await groupMarkerFor(mine, "u1", id)), true, "after m4");
});

test("[group hide] a group chat without a message hides up to the group's creation, back with the first message", async () => {
  await seedGroup("e1", "empty", ["u1", "u2"]);
  const mine = studentDb("u1");
  const id = groupMarkerId("e1", "empty");
  await assertSucceeds(hideGroupAs(mine, "u1", id, Timestamp.fromMillis(GROUP_CREATED)));
  expectEqual(isGroupChatListed(null, await groupMarkerFor(mine, "u1", id)), false, "hidden");
  const other = studentDb("u2");
  await assertSucceeds(runTransaction(other, async (transaction) => {
    transaction.set(doc(other, groupMessagePath("e1", "empty", "m1")), newMessage("u2", "Hi"));
  }));
  expectEqual(isGroupChatListed(await groupLatestFor(mine, "e1", "empty"), await groupMarkerFor(mine, "u1", id)), true, "back");
});

test("[group hide] a marker only moves forward; a stale second device keeps the newer time", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  const id = groupMarkerId("e1", "g1");
  await assertSucceeds(hideGroupAs(db, "u1", id, Timestamp.fromMillis(T3)));
  await assertFails(updateDoc(doc(db, groupMarkerPath("u1", id)), { hiddenUpTo: Timestamp.fromMillis(0) }));
  await assertFails(setDoc(doc(db, groupMarkerPath("u1", id)), { hiddenUpTo: Timestamp.fromMillis(T1) }));
  expectEqual(await assertSucceeds(hideGroupAs(db, "u1", id, Timestamp.fromMillis(T1))), "kept", "stale");
  await assertSucceeds(setDoc(doc(db, groupMarkerPath("u1", id)), { hiddenUpTo: Timestamp.fromMillis(T3) }));
  await assertSucceeds(setDoc(doc(db, groupMarkerPath("u1", id)), { hiddenUpTo: serverTimestamp() }));
});

test("[group hide] a marker names a group its owner is in, under exactly {eventId}~{groupId}", async () => {
  await seedGroupWithMessages();
  await admin((db) => setDoc(doc(db, groupPath("e2", "g1")), { memberUids: ["u2"], capacity: 4 }));
  const db = studentDb("u1");
  const at = Timestamp.fromMillis(T1);
  for (const id of ["e1~g2", "e1~missing", "e2~g1", "e1~g1~", "e1~g1~x", "e1", "~g1", "g1~e1", "u1~u2"]) {
    await assertFails(hideGroupAs(db, "u1", id, at)).catch((error) => {
      throw new Error(`a group marker ${id} was allowed: ${error.message}`);
    });
  }
  // A one-to-one marker ID in the group collection, or a group one in the one-to-one collection.
  await seedChat("u1", "u2");
  await assertFails(setDoc(doc(db, `users/u1/hiddenChats/${groupMarkerId("e1", "g1")}`), { hiddenUpTo: at }));
  await assertSucceeds(hideGroupAs(db, "u1", "e1~g1", at));
});

test("[group hide] a marker holds exactly hiddenUpTo, no later than the server's time", async () => {
  await seedGroupWithMessages();
  const marker = doc(studentDb("u1"), groupMarkerPath("u1", "e1~g1"));
  for (const data of [
    { hiddenUpTo: Timestamp.fromMillis(T3), eventId: "e1" }, {}, { hiddenUpTo: FAR_FUTURE },
    { hiddenUpTo: "2026-11-14" }, { hiddenUpTo: T3 }, { hiddenUpTo: null },
  ]) {
    await assertFails(setDoc(marker, data)).catch((error) => {
      throw new Error(`${JSON.stringify(data)} was allowed: ${error.message}`);
    });
  }
});

test("[group hide] nobody else reads or writes a marker; nobody deletes one while its owner is in the group; no query across students", async () => {
  await seedGroupWithMessages();
  const id = "e1~g1";
  await assertFails(setDoc(doc(studentDb("u2"), groupMarkerPath("u1", id)), { hiddenUpTo: Timestamp.fromMillis(T1) }));
  await assertSucceeds(hideGroupAs(studentDb("u1"), "u1", id, Timestamp.fromMillis(T3)));
  await assertFails(getDoc(doc(studentDb("u2"), groupMarkerPath("u1", id))));
  await assertFails(deleteDoc(doc(studentDb("u2"), groupMarkerPath("u1", id))));
  await assertFails(deleteDoc(doc(studentDb("u1"), groupMarkerPath("u1", id))));
  await assertFails(getDocs(collectionGroup(studentDb("u1"), "hiddenGroupChats")));
});

test("[group hide] under a deletion marker, a student reads, creates or moves none of their group markers", async () => {
  await seedGroupWithMessages();
  await assertSucceeds(hideGroupAs(studentDb("u1"), "u1", "e1~g1", Timestamp.fromMillis(T3)));
  await seedDeletedAccount("u1");
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, groupMarkerPath("u1", "e1~g1"))));
  await assertFails(getDocs(collection(db, "users/u1/hiddenGroupChats")));
  await assertFails(setDoc(doc(db, groupMarkerPath("u1", "e1~g1")), { hiddenUpTo: serverTimestamp() }));
});

for (const [name, db, uid] of [
  ["an @epfl.ch association listed as a member", () => studentDb("epflClub"), "epflClub"],
  ["a non-EPFL association listed as a member", () => associationDb("club"), "club"],
  ["an unverified @epfl.ch student", () => firestoreAs("student@epfl.ch", false, "u1"), "u1"],
  ["a signed-out user", () => env.unauthenticatedContext().firestore(), "u1"],
]) {
  test(`[group hide] ${name} has no group markers`, async () => {
    await seedGroup("e1", "g1", [uid, "u2"]);
    await assertFails(setDoc(doc(db(), groupMarkerPath(uid, "e1~g1")), { hiddenUpTo: Timestamp.fromMillis(T1) }));
    await admin((adminDb) => setDoc(doc(adminDb, groupMarkerPath(uid, "e1~g1")), { hiddenUpTo: Timestamp.fromMillis(T1) }));
    await assertFails(getDoc(doc(db(), groupMarkerPath(uid, "e1~g1"))));
    await assertFails(getDocs(collection(db(), `users/${uid}/hiddenGroupChats`)));
    // Nor deletes one, even for a group that is gone.
    await admin((adminDb) => setDoc(doc(adminDb, groupMarkerPath(uid, "e1~gone")), { hiddenUpTo: Timestamp.fromMillis(T1) }));
    await assertFails(deleteDoc(doc(db(), groupMarkerPath(uid, "e1~gone"))));
  });
}

// ---- leaving deletes the group marker (decided by the Sprint 6 PO on 2026-11-16) ----

test("[group unhide] leaving deletes the marker in the leave transaction, without reading it", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  await assertSucceeds(hideGroupAs(db, "u1", "e1~g1", Timestamp.fromMillis(T3)));
  await assertSucceeds(leaveGroupAs(db, "u1", "e1", "g1"));
  await expectMissing(groupMarkerPath("u1", "e1~g1"));
  await expectMissing("events/e1/groupMembers/u1");
  expectEqual((await stored(groupPath("e1", "g1"))).memberUids, ["u2"], "members");
  // Out of the group, no new marker.
  await assertFails(hideGroupAs(db, "u1", "e1~g1", Timestamp.fromMillis(T3)));
});

test("[group unhide] the last member's leave deletes the group, their groupMembers marker and the chat marker together", async () => {
  await seedGroup("e1", "solo", ["u1"]);
  await seedGroupMessage("e1", "solo", "m1", "u1", T1);
  const db = studentDb("u1");
  await assertSucceeds(hideGroupAs(db, "u1", "e1~solo", Timestamp.fromMillis(T1)));
  await assertSucceeds(leaveGroupAs(db, "u1", "e1", "solo"));
  for (const gone of [groupPath("e1", "solo"), "events/e1/groupMembers/u1", groupMarkerPath("u1", "e1~solo")]) {
    await expectMissing(gone);
  }
});

test("[group unhide] leaving without a marker: deleting the missing marker passes, and the leave with it", async () => {
  await seedGroupWithMessages();
  await assertSucceeds(leaveGroupAs(studentDb("u1"), "u1", "e1", "g1"));
  expectEqual((await stored(groupPath("e1", "g1"))).memberUids, ["u2"], "members");
  await expectMissing(groupMarkerPath("u1", "e1~g1"));
  await seedGroup("e2", "solo", ["u1"]);
  await assertSucceeds(leaveGroupAs(studentDb("u1"), "u1", "e2", "solo"));
  await expectMissing(groupPath("e2", "solo"));
});

test("[group unhide] a member removed from a private event still leaves, and their marker goes with it", async () => {
  await seedGroupWithMessages();
  await assertSucceeds(hideGroupAs(studentDb("u1"), "u1", "e1~g1", Timestamp.fromMillis(T3)));
  await admin((db) => setDoc(doc(db, "events/e1"), {
    title: "Event", isPrivate: true, createdBy: "u3", organizerIds: ["u3"], allowedUids: ["u3"],
  }));
  await assertSucceeds(leaveGroupAs(studentDb("u1"), "u1", "e1", "g1"));
  await expectMissing(groupMarkerPath("u1", "e1~g1"));
});

test("[group unhide] after leaving and rejoining, the chat is listed again at once, with its whole history", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  const id = groupMarkerId("e1", "g1");
  const latest = await groupLatestFor(db, "e1", "g1");
  await assertSucceeds(hideGroupAs(db, "u1", id, latest.createdAt));
  expectEqual(isGroupChatListed(latest, await groupMarkerFor(db, "u1", id)), false, "hidden");
  await assertSucceeds(leaveGroupAs(db, "u1", "e1", "g1"));
  await assertSucceeds(joinGroupAs(db, "u1", "e1", "g1"));
  expectEqual((await stored(groupPath("e1", "g1"))).memberUids, ["u2", "u1"], "members");
  const again = await groupLatestFor(db, "e1", "g1");
  expectEqual(again.createdAt.toMillis(), T3, "no new message");
  expectEqual(isGroupChatListed(again, await groupMarkerFor(db, "u1", id)), true, "listed");
  expectEqual(ids(await assertSucceeds(getDocs(collection(db, "users/u1/hiddenGroupChats")))), "", "markers");
  const history = await assertSucceeds(getDocs(groupChatOf(db, "e1", "g1")));
  expectEqual(history.docs.map((d) => d.id), ["m1", "m2", "m3"], "history");
});

test("[group unhide] a member can't delete their marker while still in the group", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  await assertSucceeds(hideGroupAs(db, "u1", "e1~g1", Timestamp.fromMillis(T3)));
  await assertFails(deleteDoc(doc(db, groupMarkerPath("u1", "e1~g1"))));
  expectEqual((await groupMarkerFor(db, "u1", "e1~g1")).hiddenUpTo.toMillis(), T3, "kept");
  // A member without a marker can't delete the missing one either.
  await assertFails(deleteDoc(doc(studentDb("u2"), groupMarkerPath("u2", "e1~g1"))));
});

test("[group unhide] a client that leaves without deleting its marker still leaves; only its owner deletes the marker later", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  await assertSucceeds(hideGroupAs(db, "u1", "e1~g1", Timestamp.fromMillis(T2)));
  await assertSucceeds(leaveGroupAs(db, "u1", "e1", "g1", { deleteMarker: false }));
  expectEqual((await stored(groupPath("e1", "g1"))).memberUids, ["u2"], "members");
  await assertFails(hideGroupAs(db, "u1", "e1~g1", Timestamp.fromMillis(T3)));
  expectEqual((await groupMarkerFor(db, "u1", "e1~g1")).hiddenUpTo.toMillis(), T2, "kept");
  for (const other of ["u2", "u3"]) {
    await assertFails(deleteDoc(doc(studentDb(other), groupMarkerPath("u1", "e1~g1"))));
  }
  await assertSucceeds(deleteDoc(doc(db, groupMarkerPath("u1", "e1~g1"))));
  await expectMissing(groupMarkerPath("u1", "e1~g1"));
});

test("[group unhide] once a group is deleted with its event, its owner may delete the marker; nobody else", async () => {
  await seedGroupWithMessages();
  await assertSucceeds(hideGroupAs(studentDb("u1"), "u1", "e1~g1", Timestamp.fromMillis(T3)));
  await admin((db) => deleteDoc(doc(db, groupPath("e1", "g1"))));
  await assertFails(deleteDoc(doc(studentDb("u2"), groupMarkerPath("u1", "e1~g1"))));
  await assertSucceeds(deleteDoc(doc(studentDb("u1"), groupMarkerPath("u1", "e1~g1"))));
  await expectMissing(groupMarkerPath("u1", "e1~g1"));
});

test("[group unhide] deleting a marker needs an ID of exactly {eventId}~{groupId}, even for a group its owner isn't in", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  for (const id of ["e1~g2~", "e1~g2~x", "e1~~g2", "~g2", "e1"]) {
    await admin((adminDb) => setDoc(doc(adminDb, groupMarkerPath("u1", id)), { hiddenUpTo: Timestamp.fromMillis(T1) }));
    await assertFails(deleteDoc(doc(db, groupMarkerPath("u1", id)))).catch((error) => {
      throw new Error(`deleting the group marker ${id} was allowed: ${error.message}`);
    });
  }
  // A well-formed ID of a group they aren't in: their own marker, which hides nothing.
  await assertSucceeds(deleteDoc(doc(db, groupMarkerPath("u1", "e1~g2"))));
});

test("[group unhide] under a deletion marker, a member still leaves, the last member too, and the leave deletes their marker", async () => {
  await seedGroupWithMessages();
  await seedGroup("e1", "solo", ["u3"]);
  await assertSucceeds(hideGroupAs(studentDb("u1"), "u1", "e1~g1", Timestamp.fromMillis(T3)));
  await assertSucceeds(hideGroupAs(studentDb("u3"), "u3", "e1~solo", Timestamp.fromMillis(GROUP_CREATED)));
  // As deleteAccount leaves them: the deletion marker written and the profile deleted.
  await admin(async (adminDb) => {
    for (const uid of ["u1", "u3"]) {
      await setDoc(doc(adminDb, `deletedAccounts/${uid}`), {});
      await deleteDoc(doc(adminDb, `users/${uid}`));
    }
  });
  await assertSucceeds(leaveGroupAs(studentDb("u1"), "u1", "e1", "g1"));
  expectEqual((await stored(groupPath("e1", "g1"))).memberUids, ["u2"], "members");
  await expectMissing(groupMarkerPath("u1", "e1~g1"));
  await assertSucceeds(leaveGroupAs(studentDb("u3"), "u3", "e1", "solo"));
  for (const gone of [groupPath("e1", "solo"), "events/e1/groupMembers/u3", groupMarkerPath("u3", "e1~solo")]) {
    await expectMissing(gone);
  }
});

test("[group unhide] under a deletion marker, the one new grant is deleting one's own marker once out of the group", async () => {
  await seedGroupWithMessages();
  for (const uid of ["u1", "u2"]) {
    await admin((adminDb) => setDoc(doc(adminDb, groupMarkerPath(uid, "e1~g1")), { hiddenUpTo: Timestamp.fromMillis(T1) }));
  }
  await seedDeletedAccount("u1");
  const db = studentDb("u1");
  const marker = doc(db, groupMarkerPath("u1", "e1~g1"));
  await assertFails(getDoc(marker));
  await assertFails(getDocs(collection(db, "users/u1/hiddenGroupChats")));
  await assertFails(setDoc(marker, { hiddenUpTo: Timestamp.fromMillis(T3) }));
  await assertFails(updateDoc(marker, { hiddenUpTo: Timestamp.fromMillis(T3) }));
  await assertFails(setDoc(doc(db, groupMarkerPath("u1", "e2~new")), { hiddenUpTo: Timestamp.fromMillis(T1) }));
  // While in the group, no delete; another student's, never.
  await assertFails(deleteDoc(marker));
  await assertFails(deleteDoc(doc(db, groupMarkerPath("u2", "e1~g1"))));
  // Out of the group (deleteAccount takes them out, #241): their own, under a two-part ID only.
  await admin((adminDb) => setDoc(doc(adminDb, groupPath("e1", "g1")), { memberUids: ["u2"] }, { merge: true }));
  await assertFails(deleteDoc(doc(db, groupMarkerPath("u2", "e1~g1"))));
  await assertFails(deleteDoc(doc(db, groupMarkerPath("u1", "e1~g1~"))));
  await assertSucceeds(deleteDoc(marker));
  await expectMissing(groupMarkerPath("u1", "e1~g1"));
  expectEqual((await stored(groupMarkerPath("u2", "e1~g1"))).hiddenUpTo.toMillis(), T1, "u2's kept");
});

test("[group unhide] in the batch that leaves, a modified client can still create its marker; it only affects its own list", async () => {
  await seedGroupWithMessages();
  const db = studentDb("u1");
  // isValidMarker() reads the group as it was before the write, when it still lists u1.
  const batch = writeBatch(db);
  batch.update(doc(db, groupPath("e1", "g1")), { memberUids: ["u2"] });
  batch.delete(doc(db, "events/e1/groupMembers/u1"));
  batch.set(doc(db, groupMarkerPath("u1", "e1~g1")), { hiddenUpTo: Timestamp.fromMillis(T1) });
  await assertSucceeds(batch.commit());
  expectEqual((await stored(groupMarkerPath("u1", "e1~g1"))).hiddenUpTo.toMillis(), T1, "created");
  // Once the leave has committed: no new write; its owner may delete it.
  await assertFails(setDoc(doc(db, groupMarkerPath("u1", "e1~g1")), { hiddenUpTo: Timestamp.fromMillis(T3) }));
  await assertSucceeds(deleteDoc(doc(db, groupMarkerPath("u1", "e1~g1"))));
});

test("[group unhide] IDs a marker ID can't be built from: the delete would fail the leave, so the app skips it", async () => {
  expectEqual(
    [groupMarkerId("e1", "g1"), groupMarkerId("ev~il", "g1"), groupMarkerId("e1", "gr~oup"),
      groupMarkerId("__a", "b__"), groupMarkerId("a".repeat(1490), "AbCdEfGhIjKlMnOpQrSt")],
    ["e1~g1", null, null, null, null],
    "marker IDs",
  );
  const event = {
    title: "T", description: "", category: "OTHER", isPrivate: false, isAssociationEvent: false,
    createdBy: "u3", organizerIds: ["u3"], allowedUids: ["u3"],
  };
  // A student creates such events and groups: no rule constrains their IDs.
  const creator = studentDb("u3");
  for (const [eventId, groupId] of [["ev~il", "g1"], ["e2", "gr~oup"], ["__a", "b__"]]) {
    await assertSucceeds(setDoc(doc(creator, `events/${eventId}`), event));
    const batch = writeBatch(creator);
    batch.set(doc(creator, groupPath(eventId, groupId)), {
      capacity: 4, memberUids: ["u3"], createdBy: "u3", createdAt: serverTimestamp(),
    });
    batch.set(doc(creator, `events/${eventId}/groupMembers/u3`), { groupId });
    await assertSucceeds(batch.commit());
    const db = studentDb("u1");
    await assertSucceeds(joinGroupAs(db, "u1", eventId, groupId));
    // The delete under the joined ID: refused by the rules for a '~', INVALID_ARGUMENT for __a~b__.
    const error = await leaveGroupAs(db, "u1", eventId, groupId, { markerId: `${eventId}~${groupId}` })
      .then(() => null, (failure) => failure);
    expectEqual(error?.code, eventId === "__a" ? "invalid-argument" : "permission-denied", `${eventId}/${groupId}`);
    expectEqual((await stored(groupPath(eventId, groupId))).memberUids, ["u3", "u1"], "still in");
    // The app's leave skips the delete and passes.
    await assertSucceeds(leaveGroupAs(db, "u1", eventId, groupId));
    expectEqual((await stored(groupPath(eventId, groupId))).memberUids, ["u3"], "left");
  }
});
