// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged).
// PROTOTYPE for #365, never merged: unsend and delete for me in one-to-one chats, from
// docs/Architecture-Diagram.md section 3.8 (38 cases). The helpers are copied from
// chats.prototype.test.js. From the repository root (firebase/firestore/PROTOTYPE-365.md):
//   firebase emulators:exec --project demo-polysocial --only firestore "node firebase/firestore/chats.deleting.prototype.test.js"
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

// ---- deleting (#365): helpers ----

const { deleteField } = require("firebase/firestore");

const T1 = 1_790_000_000_000;
const T2 = 1_790_000_060_000;
const T3 = 1_790_000_120_000;
/** 2100-01-01: later than any server time these tests run at. */
const FAR_FUTURE = Timestamp.fromMillis(4_102_444_800_000);

/**
 * Unsends as the app will: one transaction tried once that reads the message, leaves it alone if
 * it isn't the caller's or is already empty (a retry), and otherwise empties only its text.
 */
function unsendAs(db, uid, pairId, id) {
  return runTransaction(
    db,
    async (transaction) => {
      const message = doc(db, `chats/${pairId}/messages/${id}`);
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

/** The raw write the rules judge: [changes] applied to the stored message. */
function updateMessageAs(db, pairId, id, changes) {
  return updateDoc(doc(db, `chats/${pairId}/messages/${id}`), changes);
}

function markerPath(uid, pairId) {
  return `users/${uid}/hiddenChats/${pairId}`;
}

/**
 * Delete for me, as the app will: one transaction tried once that reads the stored marker and
 * sets it, unless it already holds this time or a later one (a second device with a stale row).
 */
function hideAs(db, uid, pairId, upTo) {
  return runTransaction(
    db,
    async (transaction) => {
      const marker = doc(db, markerPath(uid, pairId));
      const snapshot = await transaction.get(marker);
      if (snapshot.exists() && !isNewer(upTo, snapshot.get("hiddenUpTo"))) return "kept";
      transaction.set(marker, { hiddenUpTo: upTo });
      return "hidden";
    },
    { maxAttempts: 1 },
  );
}

/** The student's markers, as the Chats tab's one marker listener reads them. */
function myMarkers(db, uid) {
  return collection(db, `users/${uid}/hiddenChats`);
}

function isNewer(a, b) {
  return a.seconds > b.seconds || (a.seconds === b.seconds && a.nanoseconds > b.nanoseconds);
}

/**
 * The Chats tab's rule for a one-to-one chat, from its latest message and the student's marker:
 * listed once it has a message, unless that message is no newer than the marker's hiddenUpTo.
 */
function isListed(latest, marker) {
  if (!latest) return false;
  if (!marker) return true;
  return isNewer(latest.createdAt, marker.hiddenUpTo);
}

async function latestFor(db, pairId) {
  const snapshot = await assertSucceeds(getDocs(latestOf(db, pairId)));
  return snapshot.empty ? null : snapshot.docs[0].data();
}

async function markerFor(db, uid, pairId) {
  const snapshot = await assertSucceeds(getDoc(doc(db, markerPath(uid, pairId))));
  return snapshot.exists() ? snapshot.data() : null;
}

async function seedPairWithMessages() {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u1", T1);
  await seedMessage("u1~u2", "m2", "u2", T2);
  await seedMessage("u1~u2", "m3", "u1", T3);
}

// ---- unsend a message ----

test("[unsend] the sender unsends their message: both read it in place, with an empty text", async () => {
  await seedPairWithMessages();
  expectEqual(await assertSucceeds(unsendAs(studentDb("u1"), "u1", "u1~u2", "m3")), "unsent", "unsend");
  const message = await stored("chats/u1~u2/messages/m3");
  expectEqual(
    [Object.keys(message).sort(), message.senderId, message.text, message.createdAt.toMillis()],
    [["createdAt", "senderId", "text"], "u1", "", T3],
    "stored message",
  );
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    const history = await assertSucceeds(getDocs(messagesOf(db, "u1~u2")));
    expectEqual(
      history.docs.map((d) => [d.id, d.get("senderId"), d.get("text")]),
      [["m1", "u1", "Seeded"], ["m2", "u2", "Seeded"], ["m3", "u1", ""]],
      `history for ${uid}`,
    );
    // The Chats tab's preview is the unsent message, at its original time.
    const latest = await latestFor(db, "u1~u2");
    expectEqual([latest.text, latest.createdAt.toMillis()], ["", T3], `latest for ${uid}`);
  }
});

test("[unsend] a retry finds the text already empty; emptying it again is a no-op the rules allow", async () => {
  await seedPairWithMessages();
  const db = studentDb("u1");
  expectEqual(await assertSucceeds(unsendAs(db, "u1", "u1~u2", "m1")), "unsent", "first");
  expectEqual(await assertSucceeds(unsendAs(db, "u1", "u1~u2", "m1")), "already unsent", "retry");
  await assertSucceeds(updateMessageAs(db, "u1~u2", "m1", { text: "" }));
  expectEqual((await stored("chats/u1~u2/messages/m1")).text, "", "text");
});

test("[unsend] only the sender: the other student and a third student can't empty a message", async () => {
  await seedPairWithMessages();
  await seedFriendship("u1", "u3");
  await seedFriendship("u2", "u3");
  await assertFails(updateMessageAs(studentDb("u2"), "u1~u2", "m1", { text: "" }));
  await assertFails(updateMessageAs(studentDb("u3"), "u1~u2", "m1", { text: "" }));
  await assertFails(updateMessageAs(studentDb("u1"), "u1~u2", "m2", { text: "" }));
  expectEqual((await stored("chats/u1~u2/messages/m1")).text, "Seeded", "u1's text");
  expectEqual((await stored("chats/u1~u2/messages/m2")).text, "Seeded", "u2's text");
});

test("[unsend] the sender can't edit the text into anything else, remove it, or restore it", async () => {
  await seedPairWithMessages();
  const db = studentDb("u1");
  for (const text of ["Edited", " ", "\n", null, 0, false, []]) {
    await assertFails(updateMessageAs(db, "u1~u2", "m1", { text })).catch((error) => {
      throw new Error(`text ${JSON.stringify(text)} was allowed: ${error.message}`);
    });
  }
  await assertFails(updateMessageAs(db, "u1~u2", "m1", { text: deleteField() }));
  await assertSucceeds(unsendAs(db, "u1", "u1~u2", "m1"));
  await assertFails(updateMessageAs(db, "u1~u2", "m1", { text: "Seeded" }));
  await assertFails(updateMessageAs(db, "u1~u2", "m1", { text: "Something else" }));
  await assertFails(updateMessageAs(db, "u1~u2", "m1", { text: deleteField() }));
  expectEqual((await stored("chats/u1~u2/messages/m1")).text, "", "text");
});

test("[unsend] only the text changes: not senderId, not createdAt, no added field, no delete", async () => {
  await seedPairWithMessages();
  const db = studentDb("u1");
  for (const [name, changes] of [
    ["an empty senderId", { text: "", senderId: "" }],
    ["another senderId", { text: "", senderId: "u2" }],
    ["a new server time", { text: "", createdAt: serverTimestamp() }],
    ["another time", { text: "", createdAt: Timestamp.fromMillis(T1) }],
    ["no time", { text: "", createdAt: deleteField() }],
    ["a deletedAt", { text: "", deletedAt: serverTimestamp() }],
    ["an unsent flag", { text: "", unsent: true }],
  ]) {
    await assertFails(updateMessageAs(db, "u1~u2", "m3", changes)).catch((error) => {
      throw new Error(`unsend with ${name} was allowed: ${error.message}`);
    });
  }
  for (const uid of ["u1", "u2"]) {
    await assertFails(deleteDoc(doc(studentDb(uid), "chats/u1~u2/messages/m3")));
  }
  const message = await stored("chats/u1~u2/messages/m3");
  expectEqual([message.senderId, message.text, message.createdAt.toMillis()], ["u1", "Seeded", T3], "kept");
});

test("[unsend] a read-only chat can't change: not after unfriending, nor while a new request is pending", async () => {
  await seedPairWithMessages();
  await assertSucceeds(removeFriendAs("u2", "u1"));
  await assertFails(updateMessageAs(studentDb("u1"), "u1~u2", "m1", { text: "" }));
  await assertFails(updateMessageAs(studentDb("u2"), "u1~u2", "m2", { text: "" }));
  await assertSucceeds(requestAs("u1", "u2"));
  await assertFails(updateMessageAs(studentDb("u1"), "u1~u2", "m1", { text: "" }));
  await assertFails(updateMessageAs(studentDb("u2"), "u1~u2", "m2", { text: "" }));
  // Friends again: they unsend again.
  await assertSucceeds(acceptAs("u2", "u1"));
  await assertSucceeds(updateMessageAs(studentDb("u1"), "u1~u2", "m1", { text: "" }));
});

test("[unsend] under a deletion marker, a student can't unsend, even while the friendship is stored", async () => {
  await seedPairWithMessages();
  await seedDeletedAccount("u1");
  await assertFails(updateMessageAs(studentDb("u1"), "u1~u2", "m1", { text: "" }));
  expectEqual((await stored("chats/u1~u2/messages/m1")).text, "Seeded", "text");
});

test("[unsend] a message whose sender was anonymised (empty senderId) can't be unsent by anyone", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "theirs", "", T1);
  for (const uid of ["u1", "u2"]) {
    await assertFails(updateMessageAs(studentDb(uid), "u1~u2", "theirs", { text: "" }));
  }
  expectEqual((await stored("chats/u1~u2/messages/theirs")).text, "Seeded", "text");
});

for (const [name, uid, db] of [
  ["a non-EPFL association (unverified)", "club", () => associationDb("club")],
  ["a non-EPFL association (verified)", "verifiedClub", () => associationDb("verifiedClub")],
  ["an @epfl.ch association", "epflClub", () => studentDb("epflClub")],
]) {
  test(`[unsend] ${name} named in a chat can't unsend its own message`, async () => {
    const pairId = pairIdOf(uid, "u1");
    await seedFriendship(uid, "u1");
    await seedChat(uid, "u1");
    await seedMessage(pairId, "m1", uid);
    await assertFails(updateMessageAs(db(), pairId, "m1", { text: "" }));
  });
}

for (const [name, db] of [
  ["an unverified @epfl.ch student", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a student without an email_verified claim",
    () => env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore()],
  ["a verified non-EPFL account with a student's profile",
    () => firestoreAs("student@gmail.com", true, "u1")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`[unsend] ${name} can't unsend`, async () => {
    await seedPairWithMessages();
    await assertFails(updateMessageAs(db(), "u1~u2", "m1", { text: "" }));
  });
}

test("[unsend] in a chat stored under another spelling of the pair ID, the sender can't unsend", async () => {
  for (const id of ["u1~u2~", "u2~u1"]) {
    await admin(async (db) => {
      await setDoc(doc(db, `friendships/${id}`), { uids: ["u1", "u2"], status: "accepted" });
      await setDoc(doc(db, `chats/${id}`), { uids: ["u1", "u2"] });
    });
    await seedMessage(id, "m1", "u1");
    await assertFails(updateMessageAs(studentDb("u1"), id, "m1", { text: "" }));
  }
});

// Changed on 2026-11-14 when group chats got unsend too: the sender may now empty their message;
// the other member still can't, and nobody edits or deletes one.
test("[unsend] in group chat only the sender empties a message; nobody edits or deletes one", async () => {
  await admin(async (db) => {
    await setDoc(doc(db, "events/e1/groups/g1"), {
      capacity: 4, memberUids: ["u1", "u2"], createdBy: "u1", createdAt: Timestamp.fromMillis(T1),
    });
    await setDoc(doc(db, "events/e1/groups/g1/messages/m1"), {
      senderId: "u1", text: "Seeded", createdAt: Timestamp.fromMillis(T1),
    });
  });
  for (const uid of ["u1", "u2"]) {
    const message = doc(studentDb(uid), "events/e1/groups/g1/messages/m1");
    await assertSucceeds(getDoc(message));
    await assertFails(updateDoc(message, { text: "Edited" }));
    await assertFails(deleteDoc(message));
  }
  await assertFails(updateDoc(doc(studentDb("u2"), "events/e1/groups/g1/messages/m1"), { text: "" }));
  await assertSucceeds(updateDoc(doc(studentDb("u1"), "events/e1/groups/g1/messages/m1"), { text: "" }));
});

// ---- delete for me ----

test("[hide] a student hides a chat up to its latest message; the other student sees no change", async () => {
  await seedPairWithMessages();
  const mine = studentDb("u1");
  const latest = await latestFor(mine, "u1~u2");
  await assertSucceeds(hideAs(mine, "u1", "u1~u2", latest.createdAt));
  const marker = await markerFor(mine, "u1", "u1~u2");
  expectEqual(
    [Object.keys(marker), marker.hiddenUpTo.toMillis()], [["hiddenUpTo"], T3], "marker",
  );
  expectEqual(ids(await assertSucceeds(getDocs(myMarkers(mine, "u1")))), "u1~u2", "my markers");
  expectEqual(isListed(latest, marker), false, "listed for u1");
  // The chat itself is unchanged, and u1 still reads it (opened from the friends list).
  expectEqual(await stored("chats/u1~u2"), { uids: ["u1", "u2"] }, "chat");
  expectEqual(ids(await assertSucceeds(getDocs(messagesOf(mine, "u1~u2")))), "m1,m2,m3", "u1 history");
  // u2: the same chat, the same messages, no marker of their own, and no way to read u1's.
  const other = studentDb("u2");
  expectEqual(ids(await assertSucceeds(getDocs(myChats(other, "u2")))), "u1~u2", "u2 chats");
  expectEqual(ids(await assertSucceeds(getDocs(myMarkers(other, "u2")))), "", "u2 markers");
  expectEqual(isListed(await latestFor(other, "u1~u2"), null), true, "listed for u2");
  await assertFails(getDoc(doc(other, markerPath("u1", "u1~u2"))));
  await assertFails(getDocs(myMarkers(other, "u1")));
});

test("[hide] a newer message from either student brings the chat back; an unsend doesn't", async () => {
  await seedPairWithMessages();
  const mine = studentDb("u1");
  await assertSucceeds(hideAs(mine, "u1", "u1~u2", Timestamp.fromMillis(T3)));
  // Unsending the latest message keeps its time: still hidden.
  await assertSucceeds(unsendAs(mine, "u1", "u1~u2", "m3"));
  expectEqual(isListed(await latestFor(mine, "u1~u2"), await markerFor(mine, "u1", "u1~u2")), false, "after unsend");
  // A message from the other student, at the server's time: back on the list.
  await assertSucceeds(sendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Still there?"), "m4"));
  const latest = await latestFor(mine, "u1~u2");
  expectEqual(isListed(latest, await markerFor(mine, "u1", "u1~u2")), true, "after their message");
  // Hidden again (the marker is overwritten), then back with the student's own message.
  await assertSucceeds(hideAs(mine, "u1", "u1~u2", latest.createdAt));
  expectEqual(isListed(await latestFor(mine, "u1~u2"), await markerFor(mine, "u1", "u1~u2")), false, "hidden again");
  await assertSucceeds(sendAs(mine, "u1~u2", newMessage("u1", "Yes"), "m5"));
  expectEqual(isListed(await latestFor(mine, "u1~u2"), await markerFor(mine, "u1", "u1~u2")), true, "after mine");
});

test("[hide] another student's marker reads as denied whether it exists or not", async () => {
  await seedPairWithMessages();
  const other = studentDb("u2");
  await assertFails(getDoc(doc(other, markerPath("u1", "u1~u2"))));
  await assertFails(getDocs(myMarkers(other, "u1")));
  await assertSucceeds(hideAs(studentDb("u1"), "u1", "u1~u2", Timestamp.fromMillis(T3)));
  await assertFails(getDoc(doc(other, markerPath("u1", "u1~u2"))));
  await assertFails(getDocs(myMarkers(other, "u1")));
});

test("[hide] nobody writes another student's marker", async () => {
  await seedPairWithMessages();
  await seedFriendship("u1", "u3");
  const at = Timestamp.fromMillis(T3);
  await assertFails(setDoc(doc(studentDb("u2"), markerPath("u1", "u1~u2")), { hiddenUpTo: at }));
  await assertFails(setDoc(doc(studentDb("u3"), markerPath("u1", "u1~u2")), { hiddenUpTo: at }));
  await expectMissing(markerPath("u1", "u1~u2"));
  await assertSucceeds(hideAs(studentDb("u1"), "u1", "u1~u2", at));
  const earlier = Timestamp.fromMillis(T1);
  await assertFails(setDoc(doc(studentDb("u2"), markerPath("u1", "u1~u2")), { hiddenUpTo: earlier }));
  await assertFails(updateDoc(doc(studentDb("u2"), markerPath("u1", "u1~u2")), { hiddenUpTo: earlier }));
  await assertFails(deleteDoc(doc(studentDb("u2"), markerPath("u1", "u1~u2"))));
  expectEqual((await stored(markerPath("u1", "u1~u2"))).hiddenUpTo.toMillis(), T3, "kept");
});

test("[hide] a marker only names an existing chat of its owner, under its exact pair ID", async () => {
  await seedPairWithMessages();
  await seedChat("u2", "u3");
  await admin(async (db) => {
    await setDoc(doc(db, "chats/u2~u1"), { uids: ["u2", "u1"] });
    await setDoc(doc(db, "chats/u1~u2~"), { uids: ["u1", "u2"] });
    await setDoc(doc(db, "chats/u1"), { uids: ["u1"] });
  });
  const db = studentDb("u1");
  const at = Timestamp.fromMillis(T1);
  for (const id of ["u1~u3", "u2~u3", "u2~u1", "u1~u2~", "u1"]) {
    await assertFails(hideAs(db, "u1", id, at)).catch((error) => {
      throw new Error(`a marker for ${id} was allowed: ${error.message}`);
    });
  }
  await assertSucceeds(hideAs(db, "u1", "u1~u2", at));
});

test("[hide] a marker holds exactly hiddenUpTo, a time no later than the server's", async () => {
  await seedPairWithMessages();
  const db = studentDb("u1");
  const marker = doc(db, markerPath("u1", "u1~u2"));
  for (const [name, data] of [
    ["another field too", { hiddenUpTo: Timestamp.fromMillis(T3), pairId: "u1~u2" }],
    ["no field", {}],
    ["a future time", { hiddenUpTo: FAR_FUTURE }],
    ["a string", { hiddenUpTo: "2026-11-14" }],
    ["a number", { hiddenUpTo: T3 }],
    ["null", { hiddenUpTo: null }],
  ]) {
    await assertFails(setDoc(marker, data)).catch((error) => {
      throw new Error(`a marker with ${name} was allowed: ${error.message}`);
    });
  }
  await assertSucceeds(setDoc(marker, { hiddenUpTo: Timestamp.fromMillis(T3) }));
  await assertSucceeds(setDoc(marker, { hiddenUpTo: serverTimestamp() }));
  await assertFails(updateDoc(marker, { hiddenUpTo: FAR_FUTURE }));
  await assertFails(updateDoc(marker, { other: true }));
});

test("[hide] nobody deletes a marker, its owner included", async () => {
  await seedPairWithMessages();
  const db = studentDb("u1");
  await assertSucceeds(hideAs(db, "u1", "u1~u2", Timestamp.fromMillis(T3)));
  await assertFails(deleteDoc(doc(db, markerPath("u1", "u1~u2"))));
});

test("[hide] a read-only chat and a chat with a deleted account can be hidden", async () => {
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u2", T1);
  await seedDeletedAccount("u3");
  await seedChat("u1", "u3", ["u1"]);
  await seedMessage("u1~u3", "m1", "", T2);
  const db = studentDb("u1");
  await assertSucceeds(hideAs(db, "u1", "u1~u2", Timestamp.fromMillis(T1)));
  await assertSucceeds(hideAs(db, "u1", "u1~u3", Timestamp.fromMillis(T2)));
  expectEqual(ids(await assertSucceeds(getDocs(myMarkers(db, "u1")))), "u1~u2,u1~u3", "markers");
});

test("[hide] under a deletion marker, a student reads and writes none of their markers", async () => {
  await seedPairWithMessages();
  await seedChat("u1", "u3");
  await assertSucceeds(hideAs(studentDb("u1"), "u1", "u1~u2", Timestamp.fromMillis(T3)));
  await seedDeletedAccount("u1");
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, markerPath("u1", "u1~u2"))));
  await assertFails(getDocs(myMarkers(db, "u1")));
  await assertFails(hideAs(db, "u1", "u1~u2", Timestamp.fromMillis(T1)));
  await assertFails(hideAs(db, "u1", "u1~u3", Timestamp.fromMillis(T1)));
  await expectMissing(markerPath("u1", "u1~u3"));
});

for (const [name, uid, db] of [
  ["a non-EPFL association (unverified)", "club", () => associationDb("club")],
  ["a non-EPFL association (verified)", "verifiedClub", () => associationDb("verifiedClub")],
  ["an @epfl.ch association", "epflClub", () => studentDb("epflClub")],
]) {
  test(`[hide] ${name} named in a chat has no markers`, async () => {
    const pairId = pairIdOf(uid, "u1");
    await seedChat(uid, "u1");
    await assertFails(hideAs(db(), uid, pairId, Timestamp.fromMillis(T1)));
    await admin((adminDb) => setDoc(doc(adminDb, markerPath(uid, pairId)), { hiddenUpTo: Timestamp.fromMillis(T1) }));
    await assertFails(getDoc(doc(db(), markerPath(uid, pairId))));
    await assertFails(getDocs(myMarkers(db(), uid)));
  });
}

for (const [name, db] of [
  ["an unverified @epfl.ch student", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a student without an email_verified claim",
    () => env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore()],
  ["a verified non-EPFL account with a student's profile",
    () => firestoreAs("student@gmail.com", true, "u1")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`[hide] ${name} has no markers`, async () => {
    await seedPairWithMessages();
    await assertFails(hideAs(db(), "u1", "u1~u2", Timestamp.fromMillis(T1)));
    await admin((adminDb) => setDoc(doc(adminDb, markerPath("u1", "u1~u2")), { hiddenUpTo: Timestamp.fromMillis(T1) }));
    await assertFails(getDoc(doc(db(), markerPath("u1", "u1~u2"))));
    await assertFails(getDocs(myMarkers(db(), "u1")));
  });
}

test("[hide] no query reaches another student's markers or every student's", async () => {
  await seedPairWithMessages();
  await assertSucceeds(hideAs(studentDb("u2"), "u2", "u1~u2", Timestamp.fromMillis(T3)));
  const db = studentDb("u1");
  await assertFails(getDocs(myMarkers(db, "u2")));
  await assertFails(getDocs(collectionGroup(db, "hiddenChats")));
});

test("[hide] a stored marker only moves forward: an earlier time, even 1970, is denied", async () => {
  await seedPairWithMessages();
  const db = studentDb("u1");
  const marker = doc(db, markerPath("u1", "u1~u2"));
  await assertSucceeds(setDoc(marker, { hiddenUpTo: Timestamp.fromMillis(T2) }));
  for (const [name, earlier] of [["T1", Timestamp.fromMillis(T1)], ["the epoch", Timestamp.fromMillis(0)]]) {
    await assertFails(updateDoc(marker, { hiddenUpTo: earlier })).catch((error) => {
      throw new Error(`moving back to ${name} was allowed: ${error.message}`);
    });
    await assertFails(setDoc(marker, { hiddenUpTo: earlier }));
  }
  expectEqual((await stored(markerPath("u1", "u1~u2"))).hiddenUpTo.toMillis(), T2, "kept");
  // The same time again (a retry) and a later one are allowed.
  await assertSucceeds(setDoc(marker, { hiddenUpTo: Timestamp.fromMillis(T2) }));
  await assertSucceeds(updateDoc(marker, { hiddenUpTo: Timestamp.fromMillis(T3) }));
  expectEqual(isListed(await latestFor(db, "u1~u2"), await markerFor(db, "u1", "u1~u2")), false, "hidden");
});

test("[hide] a second device with a stale row keeps the newer time: the transaction reads the marker", async () => {
  await seedPairWithMessages();
  const db = studentDb("u1");
  expectEqual(await assertSucceeds(hideAs(db, "u1", "u1~u2", Timestamp.fromMillis(T3))), "hidden", "first");
  expectEqual(await assertSucceeds(hideAs(db, "u1", "u1~u2", Timestamp.fromMillis(T1))), "kept", "stale");
  expectEqual(await assertSucceeds(hideAs(db, "u1", "u1~u2", Timestamp.fromMillis(T3))), "kept", "retry");
  expectEqual((await stored(markerPath("u1", "u1~u2"))).hiddenUpTo.toMillis(), T3, "stored");
  expectEqual(isListed(await latestFor(db, "u1~u2"), await markerFor(db, "u1", "u1~u2")), false, "hidden");
});

test("[hide] other subcollections of a chat or a marker: no get or write; existing group-wide lists find nothing", async () => {
  await seedPairWithMessages();
  await assertSucceeds(hideAs(studentDb("u1"), "u1", "u1~u2", Timestamp.fromMillis(T3)));
  const db = studentDb("u1");
  for (const p of ["chats/u1~u2/other/x", "chats/u1~u2/groups/x", "chats/u1~u2/attendees/u1",
    "users/u1/hiddenChats/u1~u2/other/x", "users/u1/hiddenChats/u1~u2/groups/x"]) {
    await assertFails(getDoc(doc(db, p))).catch((error) => {
      throw new Error(`get ${p} was allowed: ${error.message}`);
    });
    await assertFails(setDoc(doc(db, p), { memberUids: ["u1"], uid: "u1" })).catch((error) => {
      throw new Error(`create ${p} was allowed: ${error.message}`);
    });
  }
  await assertFails(getDocs(collection(db, "chats/u1~u2/other")));
  // The existing collection-group list rules (#121's attendees, #232's groups) match any path, so a
  // list filtered on the caller's own UID is allowed there; nothing can be written, so it is empty.
  for (const [p, field, op] of [
    ["chats/u1~u2/groups", "memberUids", "array-contains"],
    ["chats/u1~u2/attendees", "uid", "=="],
    ["users/u1/hiddenChats/u1~u2/groups", "memberUids", "array-contains"],
  ]) {
    const result = await assertSucceeds(getDocs(query(collection(db, p), where(field, op, "u1"))));
    expectEqual(result.size, 0, `${p} list`);
  }
});

test("[deletion] between the deletion marker and the friendships step, the other student can still start the chat", async () => {
  await seedFriendship("u1", "u2");
  await seedDeletedAccount("u1");
  await assertSucceeds(sendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Hi"), "m1"));
  expectEqual(await stored("chats/u1~u2"), { uids: ["u1", "u2"] }, "chat");
  // The deleted account can't, even with a friendship still stored, and from the friendships step
  // on nobody can.
  await seedFriendship("u1", "u3");
  await assertFails(sendAs(studentDb("u1"), "u1~u3", newMessage("u1")));
  await expectMissing("chats/u1~u3");
  await admin((adminDb) => deleteDoc(doc(adminDb, "friendships/u1~u2")));
  await admin((adminDb) => deleteDoc(doc(adminDb, "chats/u1~u2/messages/m1")));
  await admin((adminDb) => deleteDoc(doc(adminDb, "chats/u1~u2")));
  await assertFails(sendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Again"), "m2"));
  await expectMissing("chats/u1~u2");
});
