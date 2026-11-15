// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged).
// PROTOTYPE for #365, never merged: the one-to-one chat rules of docs/Architecture-Diagram.md
// section 3.8 (85 cases). From the repository root (firebase/firestore/PROTOTYPE-365.md):
//   firebase emulators:exec --project demo-polysocial --only firestore "node firebase/firestore/chats.prototype.test.js"
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

// ---- the whole flow, through the rules only ----

test("request, accept, chat, unfriend (read-only), friends again: the same chat continues", async () => {
  await assertSucceeds(requestAs("u1", "u2"));
  // Pending: neither can start the chat.
  await assertFails(sendAs(studentDb("u1"), "u1~u2", newMessage("u1"), "m0"));
  await assertFails(sendAs(studentDb("u2"), "u1~u2", newMessage("u2"), "m0"));
  await assertSucceeds(acceptAs("u2", "u1"));
  // Friends: the first message creates the chat, the reply only adds a message.
  await assertSucceeds(sendAs(studentDb("u1"), "u1~u2", newMessage("u1", "Hi"), "m1"));
  expectEqual(await stored("chats/u1~u2"), { uids: ["u1", "u2"] }, "chat");
  await assertSucceeds(sendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Hello"), "m2"));
  // u1 removes u2: both keep reading, neither sends.
  await assertSucceeds(removeFriendAs("u1", "u2"));
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    expectEqual(ids(await assertSucceeds(getDocs(messagesOf(db, "u1~u2")))), "m1,m2", "history");
    expectEqual(ids(await assertSucceeds(getDocs(myChats(db, uid)))), "u1~u2", "listed");
    await assertSucceeds(getDoc(doc(db, "chats/u1~u2")));
    await assertFails(sendAs(db, "u1~u2", newMessage(uid), "m3"));
  }
  await expectMissing("chats/u1~u2/messages/m3");
  // A new request is pending: still read-only.
  await assertSucceeds(requestAs("u2", "u1"));
  await assertFails(sendAs(studentDb("u1"), "u1~u2", newMessage("u1"), "m3"));
  await assertFails(sendAs(studentDb("u2"), "u1~u2", newMessage("u2"), "m3"));
  // Friends again: the same chat continues, with its history.
  await assertSucceeds(acceptAs("u1", "u2"));
  await assertSucceeds(sendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Back"), "m3"));
  expectEqual(
    ids(await assertSucceeds(getDocs(messagesOf(studentDb("u1"), "u1~u2")))), "m1,m2,m3", "history",
  );
});

// ---- creating chats/{pairId} ----

for (const uid of ["u1", "u2"]) {
  test(`a friend (${uid}) creates the chat with the first message in one transaction`, async () => {
    await seedFriendship("u1", "u2");
    await assertSucceeds(sendAs(studentDb(uid), "u1~u2", newMessage(uid)));
    expectEqual(await stored("chats/u1~u2"), { uids: ["u1", "u2"] }, "chat");
    const message = await stored("chats/u1~u2/messages/new");
    expectEqual([message.senderId, message.text], [uid, "See you at 6?"], "message");
  });
}

test("a friend creates the chat and the first message in one batch too", async () => {
  await seedFriendship("u1", "u2");
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] });
  batch.set(doc(db, "chats/u1~u2/messages/new"), newMessage("u1"));
  await assertSucceeds(batch.commit());
});

test("the chat document alone is allowed for friends (the rules can't require a message)", async () => {
  await seedFriendship("u1", "u2");
  await assertSucceeds(setDoc(doc(studentDb("u1"), "chats/u1~u2"), { uids: ["u1", "u2"] }));
});

test("a third student can't create a chat for a pair they aren't in", async () => {
  await seedFriendship("u1", "u2");
  await assertFails(setDoc(doc(studentDb("u3"), "chats/u1~u2"), { uids: ["u1", "u2"] }));
  await assertFails(setDoc(doc(studentDb("u3"), "chats/u1~u2"), { uids: ["u1", "u3"] }));
  await assertFails(sendAs(studentDb("u3"), "u1~u2", newMessage("u3")));
  await expectMissing("chats/u1~u2");
  await expectMissing("chats/u1~u2/messages/new");
});

test("a chat under an unsorted pair ID is denied, even with a friendship seeded there", async () => {
  await seedFriendship("u1", "u2");
  await seedFriendship("u1", "u2", "accepted", "u2~u1");
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "chats/u2~u1"), { uids: ["u2", "u1"] }));
  await assertFails(setDoc(doc(db, "chats/u2~u1"), { uids: ["u1", "u2"] }));
  await expectMissing("chats/u2~u1");
  // Nothing is readable under it either, even seeded with the rules off.
  await seedChat("u1", "u2", ["u1", "u2"], "u2~u1");
  await seedMessage("u2~u1", "m1", "u2");
  await assertFails(getDoc(doc(db, "chats/u2~u1")));
  await assertFails(getDocs(messagesOf(db, "u2~u1")));
  await assertFails(sendOnlyMessageAs(db, "u2~u1", newMessage("u1")));
});

test("no other subcollection of a chat is reachable", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await admin((db) => setDoc(doc(db, "chats/u1~u2/reads/u1"), { at: 1 }));
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, "chats/u1~u2/reads/u1")));
  await assertFails(setDoc(doc(db, "chats/u1~u2/reads/u1"), { at: 2 }));
  await assertFails(getDocs(collection(db, "chats/u1~u2/reads")));
});

for (const [name, id] of [
  ["one UID", "u1"],
  ["three UIDs", "u1~u2~u3"],
  ["the same UID twice", "u1~u1"],
  ["an empty first UID", "~u1"],
  ["another separator", "u1_u2"],
  // split('~') drops trailing empty parts in the rules, so these would read as the pair [u1, u2].
  ["a trailing separator", "u1~u2~"],
  ["several trailing separators", "u1~u2~~~"],
  ["a doubled separator", "u1~~u2"],
]) {
  test(`a chat whose ID is ${name} is denied, even with a friendship seeded there`, async () => {
    await admin((db) =>
      setDoc(doc(db, `friendships/${id}`), { uids: ["u1", "u2"], status: "accepted" }),
    );
    const db = studentDb("u1");
    await assertFails(setDoc(doc(db, `chats/${id}`), { uids: ["u1", "u2"] }));
    await assertFails(setDoc(doc(db, `chats/${id}`), { uids: id.split("~") }));
    await assertFails(getDoc(doc(db, `chats/${id}`)));
    await assertFails(getDocs(messagesOf(db, id)));
    // Even with a chat stored there with the rules off, nobody sends in it.
    await admin((adminDb) => setDoc(doc(adminDb, `chats/${id}`), { uids: ["u1", "u2"] }));
    await assertFails(sendOnlyMessageAs(db, id, newMessage("u1")));
    await expectMissing(`chats/${id}/messages/new`);
  });
}

for (const alias of ["u1~u2~", "u1~u2~~~"]) {
  test(`an alias of the pair's ID (${alias}) is no second chat, even with a friendship stored under it`, async () => {
    await seedFriendship("u1", "u2");
    await seedFriendship("u1", "u2", "accepted", alias);
    const db = studentDb("u1");
    // The first message can't start a chat under the alias, in a batch or in a transaction.
    const batch = writeBatch(db);
    batch.set(doc(db, `chats/${alias}`), { uids: ["u1", "u2"] });
    batch.set(doc(db, `chats/${alias}/messages/m1`), newMessage("u1"));
    await assertFails(batch.commit());
    await assertFails(sendAs(db, alias, newMessage("u1")));
    await expectMissing(`chats/${alias}`);
    await expectMissing(`chats/${alias}/messages/m1`);
    // The real chat still starts, and it is the only one the other student lists.
    await assertSucceeds(sendAs(db, "u1~u2", newMessage("u1")));
    expectEqual(ids(await assertSucceeds(getDocs(myChats(studentDb("u2"), "u2")))), "u1~u2", "listed");
    // Even stored with the rules off, nobody reads or sends under the alias.
    await seedChat("u1", "u2", ["u1", "u2"], alias);
    await seedMessage(alias, "seeded", "u1");
    for (const uid of ["u1", "u2", "u3"]) {
      const other = studentDb(uid);
      await assertFails(getDoc(doc(other, `chats/${alias}`)));
      await assertFails(getDoc(doc(other, `chats/${alias}/messages/seeded`)));
      await assertFails(getDocs(messagesOf(other, alias)));
      await assertFails(getDocs(latestOf(other, alias)));
      await assertFails(sendOnlyMessageAs(other, alias, newMessage(uid), "m2"));
    }
    await expectMissing(`chats/${alias}/messages/m2`);
  });

  test(`with only the real friendship, nothing is reachable under the alias ${alias}`, async () => {
    await seedFriendship("u1", "u2");
    const db = studentDb("u1");
    await assertFails(getDoc(doc(db, `chats/${alias}`)));
    await assertFails(getDocs(messagesOf(db, alias)));
    await assertFails(setDoc(doc(db, `chats/${alias}`), { uids: ["u1", "u2"] }));
    await assertFails(sendAs(db, alias, newMessage("u1")));
    // The prototype of #338's friendship rule refuses a friendship under the alias too.
    await assertFails(
      setDoc(doc(db, `friendships/${alias}`), {
        uids: ["u1", "u2"], requestedBy: "u1", status: "pending", createdAt: serverTimestamp(),
      }),
    );
  });
}

test("a student reads a chat with an unknown UID as missing, and can store nothing there", async () => {
  const db = studentDb("u1");
  for (const id of ["u1~zzz", "u1~u2 ", "nobody~u1"]) {
    const snapshot = await assertSucceeds(getDoc(doc(db, `chats/${id}`)));
    if (snapshot.exists()) throw new Error(`Unexpected chat ${id}`);
    expectEqual((await assertSucceeds(getDocs(messagesOf(db, id)))).size, 0, "messages");
    await assertFails(setDoc(doc(db, `chats/${id}`), { uids: id.split("~") }));
    await assertFails(sendAs(db, id, newMessage("u1")));
    await expectMissing(`chats/${id}`);
  }
});

for (const [name, data] of [
  ["the two UIDs in the wrong order", { uids: ["u2", "u1"] }],
  ["only the caller", { uids: ["u1"] }],
  ["another pair", { uids: ["u1", "u3"] }],
  ["a third UID", { uids: ["u1", "u2", "u3"] }],
  ["uids as a map", { uids: { u1: true, u2: true } }],
  ["uids as a string", { uids: "u1~u2" }],
  ["no uids", {}],
  ["another field", { uids: ["u1", "u2"], lastMessage: "Hi" }],
  ["a createdAt", { uids: ["u1", "u2"], createdAt: serverTimestamp() }],
]) {
  test(`a chat with ${name} is denied`, async () => {
    await seedFriendship("u1", "u2");
    await seedFriendship("u1", "u3");
    await assertFails(setDoc(doc(studentDb("u1"), "chats/u1~u2"), data));
    await expectMissing("chats/u1~u2");
  });
}

test("without a friendship document, nobody creates the chat or sends", async () => {
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await assertFails(sendAs(db, "u1~u2", newMessage(uid)));
  }
  await expectMissing("chats/u1~u2");
});

test("a pending friendship lets neither the sender nor the recipient create the chat", async () => {
  await seedFriendship("u1", "u2", "pending");
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await assertFails(sendAs(db, "u1~u2", newMessage(uid)));
  }
  await expectMissing("chats/u1~u2");
});

for (const [name, friendship] of [
  ["no status", { uids: ["u1", "u2"] }],
  ["another status", { uids: ["u1", "u2"], status: "blocked" }],
  ["ACCEPTED in capitals", { uids: ["u1", "u2"], status: "ACCEPTED" }],
  ["status true", { uids: ["u1", "u2"], status: true }],
  ["a null status", { uids: ["u1", "u2"], status: null }],
]) {
  test(`a friendship document with ${name} is not a friendship: no chat, no message`, async () => {
    await admin((db) => setDoc(doc(db, "friendships/u1~u2"), friendship));
    const db = studentDb("u1");
    await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await seedChat("u1", "u2");
    await assertFails(sendAs(db, "u1~u2", newMessage("u1")));
    await expectMissing("chats/u1~u2/messages/new");
  });
}

test("a friendship with someone else doesn't open a chat with a third student", async () => {
  await seedFriendship("u1", "u3");
  await assertFails(setDoc(doc(studentDb("u1"), "chats/u1~u2"), { uids: ["u1", "u2"] }));
  await assertFails(sendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
});

test("nobody changes or deletes a chat document, not even with the same content", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  for (const uid of ["u1", "u2", "u3"]) {
    const chat = doc(studentDb(uid), "chats/u1~u2");
    await assertFails(setDoc(chat, { uids: ["u1", "u2"] }));
    await assertFails(updateDoc(chat, { uids: ["u1"] }));
    await assertFails(updateDoc(chat, { uids: ["u1", "u2", "u3"] }));
    await assertFails(updateDoc(chat, { hidden: true }));
    await assertFails(deleteDoc(chat));
  }
  expectEqual(await stored("chats/u1~u2"), { uids: ["u1", "u2"] }, "chat");
});

// ---- both send their first message at once ----

test("a first message that lost the creation race fails whole, and its rerun adds only the message", async () => {
  await seedFriendship("u1", "u2");
  const db = studentDb("u1");
  // u1 reads the chat as missing; u2's first message creates it before u1 commits.
  let code = "committed";
  try {
    await runTransaction(
      db,
      async (transaction) => {
        const chat = doc(db, "chats/u1~u2");
        const snapshot = await transaction.get(chat);
        if (snapshot.exists()) throw new Error("The chat should be missing at the first read");
        await sendAs(studentDb("u2"), "u1~u2", newMessage("u2", "First!"), "fromU2");
        transaction.set(chat, { uids: ["u1", "u2"] });
        transaction.set(doc(db, "chats/u1~u2/messages/fromU1"), newMessage("u1", "Me too"));
      },
      { maxAttempts: 1 },
    );
  } catch (error) {
    code = error.code;
  }
  console.log(`# the losing first message failed with: ${code}`);
  if (!["already-exists", "permission-denied", "aborted", "failed-precondition"].includes(code)) {
    throw new Error(`The losing transaction ended with ${code}`);
  }
  await expectMissing("chats/u1~u2/messages/fromU1");
  // The rerun reads the chat as existing and writes only the message.
  await assertSucceeds(sendAs(db, "u1~u2", newMessage("u1", "Me too"), "fromU1"));
  expectEqual(ids(await getDocs(messagesOf(db, "u1~u2"))), "fromU1,fromU2", "both messages");
  expectEqual(await stored("chats/u1~u2"), { uids: ["u1", "u2"] }, "chat");
});

test("a batch that writes the chat document again with a message is denied as a whole", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] });
  batch.set(doc(db, "chats/u1~u2/messages/new"), newMessage("u1"));
  await assertFails(batch.commit());
  await expectMissing("chats/u1~u2/messages/new");
});

// ---- reading the chat document and listing chats ----

test("each of the two students gets the chat document; a third student doesn't", async () => {
  await seedChat("u1", "u2");
  await assertSucceeds(getDoc(doc(studentDb("u1"), "chats/u1~u2")));
  await assertSucceeds(getDoc(doc(studentDb("u2"), "chats/u1~u2")));
  await assertFails(getDoc(doc(studentDb("u3"), "chats/u1~u2")));
});

test("the two students read a chat that doesn't exist yet as missing; a third student is denied", async () => {
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    const snapshot = await assertSucceeds(getDoc(doc(db, "chats/u1~u2")));
    if (snapshot.exists()) throw new Error("Unexpected chat");
    const messages = await assertSucceeds(getDocs(messagesOf(db, "u1~u2")));
    expectEqual(messages.size, 0, "messages");
    expectEqual((await assertSucceeds(getDocs(latestOf(db, "u1~u2")))).size, 0, "latest");
  }
  await assertFails(getDoc(doc(studentDb("u3"), "chats/u1~u2")));
  await assertFails(getDocs(messagesOf(studentDb("u3"), "u1~u2")));
});

test("a student lists exactly their own chats with uids array-contains their UID", async () => {
  await seedChat("u1", "u2");
  await seedChat("u1", "u3");
  await seedChat("u2", "u3");
  expectEqual(ids(await assertSucceeds(getDocs(myChats(studentDb("u1"), "u1")))), "u1~u2,u1~u3", "u1");
  expectEqual(ids(await assertSucceeds(getDocs(myChats(studentDb("u3"), "u3")))), "u1~u3,u2~u3", "u3");
});

test("a student with no chat lists none", async () => {
  await seedChat("u2", "u3");
  expectEqual((await assertSucceeds(getDocs(myChats(studentDb("u1"), "u1")))).size, 0, "chats");
});

test("nobody lists chats without the array-contains filter on their own UID", async () => {
  await seedChat("u1", "u2");
  await seedChat("u2", "u3");
  const db = studentDb("u1");
  await assertFails(getDocs(collection(db, "chats")));
  await assertFails(getDocs(query(collection(db, "chats"), limit(1))));
  await assertFails(getDocs(myChats(db, "u2")));
  await assertFails(getDocs(myChats(db, "u3")));
  await assertFails(
    getDocs(query(collection(db, "chats"), where("uids", "array-contains-any", ["u1", "u2"]))),
  );
  await assertFails(getDocs(query(collection(db, "chats"), where("uids", "==", ["u2", "u3"]))));
  await assertFails(getDocs(query(collection(db, "chats"), where(documentId(), "==", "u2~u3"))));
  await assertFails(getDocs(query(collection(db, "chats"), where(documentId(), ">=", "u1~"))));
  await assertFails(
    getDocs(query(collection(db, "chats"), where(documentId(), "in", ["u1~u2", "u2~u3"]))),
  );
  await assertFails(getDocs(query(collection(db, "chats"), orderBy("uids"))));
});

test("an unfiltered list is denied even when every stored chat is the caller's", async () => {
  await seedChat("u1", "u2");
  await seedChat("u1", "u3");
  const db = studentDb("u1");
  await assertFails(getDocs(collection(db, "chats")));
  await assertFails(getDocs(query(collection(db, "chats"), limit(10))));
});

test("a query that can only return the caller's own chats is allowed (uids equal to their pair)", async () => {
  await seedChat("u1", "u2");
  await seedChat("u2", "u3");
  const own = query(collection(studentDb("u1"), "chats"), where("uids", "==", ["u1", "u2"]));
  expectEqual(ids(await assertSucceeds(getDocs(own))), "u1~u2", "own pair");
  const byId = query(collection(studentDb("u1"), "chats"), where(documentId(), "==", "u1~u2"));
  expectEqual(ids(await assertSucceeds(getDocs(byId))), "u1~u2", "own chat by ID");
});

test("nobody lists messages across chats (collection group)", async () => {
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u1");
  const db = studentDb("u1");
  await assertFails(getDocs(collectionGroup(db, "messages")));
  await assertFails(getDocs(query(collectionGroup(db, "messages"), where("senderId", "==", "u1"))));
  await assertFails(getDocs(collectionGroup(db, "chats")));
});

// ---- reading messages ----

test("both students read the chat in the server's time order, and its latest message", async () => {
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "later", "u2", 1_790_000_060_000);
  await seedMessage("u1~u2", "earlier", "u1", 1_790_000_000_000);
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    await assertSucceeds(getDoc(doc(db, "chats/u1~u2/messages/later")));
    const chat = await assertSucceeds(getDocs(messagesOf(db, "u1~u2")));
    expectEqual(chat.docs.map((d) => d.id), ["earlier", "later"], "order");
    const latest = await assertSucceeds(getDocs(latestOf(db, "u1~u2")));
    expectEqual(latest.docs.map((d) => d.id), ["later"], "latest");
  }
});

test("a third student can't read a chat's messages, even as a friend of both", async () => {
  await seedFriendship("u1", "u3");
  await seedFriendship("u2", "u3");
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u1");
  const db = studentDb("u3");
  await assertFails(getDoc(doc(db, "chats/u1~u2/messages/m1")));
  await assertFails(getDocs(messagesOf(db, "u1~u2")));
  await assertFails(getDocs(latestOf(db, "u1~u2")));
});

test("a UID that only starts like a participant's reads nothing", async () => {
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u1");
  for (const uid of ["u", "u1~", "u1~u2", "u10", "U1"]) {
    const db = studentDb(uid);
    await assertFails(getDoc(doc(db, "chats/u1~u2")));
    await assertFails(getDocs(messagesOf(db, "u1~u2")));
  }
});

test("after unfriending, both students still read the chat and its messages", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u1");
  await assertSucceeds(removeFriendAs("u2", "u1"));
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    await assertSucceeds(getDoc(doc(db, "chats/u1~u2")));
    await assertSucceeds(getDoc(doc(db, "chats/u1~u2/messages/m1")));
    expectEqual(ids(await assertSucceeds(getDocs(messagesOf(db, "u1~u2")))), "m1", "history");
    expectEqual(ids(await assertSucceeds(getDocs(myChats(db, uid)))), "u1~u2", "listed");
  }
});

// ---- sending ----

test("a friend sends in an existing chat in a transaction, as the app does", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await assertSucceeds(sendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
  await assertSucceeds(sendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Yes!"), "reply"));
  const message = await stored("chats/u1~u2/messages/new");
  expectEqual([message.senderId, message.text], ["u1", "See you at 6?"], "message");
});

test("a student can't send as the other student", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await assertFails(sendAs(studentDb("u1"), "u1~u2", newMessage("u2")));
  await assertFails(sendAs(studentDb("u2"), "u1~u2", newMessage("u1")));
  await expectMissing("chats/u1~u2/messages/new");
});

test("a third student can't send to a chat, as themselves or as a participant", async () => {
  await seedFriendship("u1", "u2");
  await seedFriendship("u1", "u3");
  await seedChat("u1", "u2");
  const db = studentDb("u3");
  await assertFails(sendOnlyMessageAs(db, "u1~u2", newMessage("u3")));
  await assertFails(sendOnlyMessageAs(db, "u1~u2", newMessage("u1")));
  await expectMissing("chats/u1~u2/messages/new");
});

test("after unfriending, neither student sends", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await assertSucceeds(sendAs(studentDb("u2"), "u1~u2", newMessage("u2"), "before"));
  await assertSucceeds(removeFriendAs("u1", "u2"));
  await assertFails(sendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
  await assertFails(sendAs(studentDb("u2"), "u1~u2", newMessage("u2")));
  await expectMissing("chats/u1~u2/messages/new");
});

test("a pending request doesn't reopen an existing chat for sending", async () => {
  await seedFriendship("u1", "u2", "pending");
  await seedChat("u1", "u2");
  await assertFails(sendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
  await assertFails(sendAs(studentDb("u2"), "u1~u2", newMessage("u2")));
  await expectMissing("chats/u1~u2/messages/new");
});

test("a message without its chat document is denied", async () => {
  await seedFriendship("u1", "u2");
  await assertFails(sendOnlyMessageAs(studentDb("u1"), "u1~u2", newMessage("u1")));
  await expectMissing("chats/u1~u2/messages/new");
});

for (const [name, changes] of [
  ["an empty text", { text: "" }],
  ["a text of 1001 characters", { text: "a".repeat(1001) }],
  ["501 emoji (1002 UTF-16 units)", { text: "\u{1F600}".repeat(501) }],
  ["a text that isn't a string", { text: 42 }],
  ["a null text", { text: null }],
  ["no text", { text: undefined }],
  ["no sender", { senderId: undefined }],
  ["the deleted-author marker as sender", { senderId: "" }],
  ["a createdAt the client chose", { createdAt: Timestamp.fromMillis(1_790_000_000_000) }],
  ["no createdAt", { createdAt: undefined }],
  ["another field", { editedAt: serverTimestamp() }],
]) {
  test(`a message with ${name} is denied`, async () => {
    await seedFriendship("u1", "u2");
    await seedChat("u1", "u2");
    const message = { ...newMessage("u1"), ...changes };
    for (const key of Object.keys(message)) {
      if (message[key] === undefined) delete message[key];
    }
    await assertFails(sendAs(studentDb("u1"), "u1~u2", message));
    await expectMissing("chats/u1~u2/messages/new");
  });
}

test("the text limit allows 1 and 1000 characters, and 500 emoji", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  const db = studentDb("u1");
  await assertSucceeds(sendAs(db, "u1~u2", newMessage("u1", "a"), "shortest"));
  await assertSucceeds(sendAs(db, "u1~u2", newMessage("u1", "a".repeat(1000)), "longest"));
  await assertSucceeds(sendAs(db, "u1~u2", newMessage("u1", "\u{1F600}".repeat(500)), "emoji"));
});

test("nobody edits or deletes a message, not even its sender", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u1");
  for (const uid of ["u1", "u2", "u3"]) {
    const message = doc(studentDb(uid), "chats/u1~u2/messages/m1");
    await assertFails(updateDoc(message, { text: "Edited" }));
    await assertFails(updateDoc(message, { senderId: "" }));
    await assertFails(setDoc(message, newMessage("u1", "Overwritten")));
    await assertFails(deleteDoc(message));
  }
  expectEqual((await stored("chats/u1~u2/messages/m1")).text, "Seeded", "text");
});

test("a retry under the same message ID reads the stored message; writing it again is denied", async () => {
  await seedFriendship("u1", "u2");
  const db = studentDb("u1");
  expectEqual(await assertSucceeds(sendAs(db, "u1~u2", newMessage("u1", "Once"), "draft")), "sent", "first");
  expectEqual(
    await assertSucceeds(sendAs(db, "u1~u2", newMessage("u1", "Once"), "draft")), "already stored", "retry",
  );
  await assertFails(sendOnlyMessageAs(db, "u1~u2", newMessage("u1", "Once"), "draft"));
});

// ---- associations, unverified emails, signed out ----

for (const [name, uid, db] of [
  ["a non-EPFL association (unverified)", "club", () => associationDb("club")],
  ["a non-EPFL association (verified)", "verifiedClub", () => associationDb("verifiedClub")],
  ["an @epfl.ch association", "epflClub", () => studentDb("epflClub")],
]) {
  test(`${name} named in a chat can't read, list, create or send`, async () => {
    const pairId = pairIdOf(uid, "u1");
    const uids = pairId.split("~");
    await seedFriendship(uid, "u1");
    // Not yet created: the association can't create it or read it as missing.
    await assertFails(getDoc(doc(db(), `chats/${pairId}`)));
    await assertFails(setDoc(doc(db(), `chats/${pairId}`), { uids }));
    await assertFails(sendAs(db(), pairId, newMessage(uid)));
    await expectMissing(`chats/${pairId}`);
    // Seeded with the rules off: still nothing.
    await seedChat(uid, "u1");
    await seedMessage(pairId, "m1", "u1");
    await assertFails(getDoc(doc(db(), `chats/${pairId}`)));
    await assertFails(getDocs(myChats(db(), uid)));
    await assertFails(getDoc(doc(db(), `chats/${pairId}/messages/m1`)));
    await assertFails(getDocs(messagesOf(db(), pairId)));
    await assertFails(sendAs(db(), pairId, newMessage(uid)));
    await expectMissing(`chats/${pairId}/messages/new`);
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
  test(`${name} can't read, list, create or send in a chat`, async () => {
    await seedFriendship("u1", "u2");
    await assertFails(setDoc(doc(db(), "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await expectMissing("chats/u1~u2");
    await seedChat("u1", "u2");
    await seedMessage("u1~u2", "m1", "u2");
    await assertFails(getDoc(doc(db(), "chats/u1~u2")));
    await assertFails(getDocs(myChats(db(), "u1")));
    await assertFails(getDoc(doc(db(), "chats/u1~u2/messages/m1")));
    await assertFails(getDocs(messagesOf(db(), "u1~u2")));
    await assertFails(sendOnlyMessageAs(db(), "u1~u2", newMessage("u1")));
    await expectMissing("chats/u1~u2/messages/new");
  });
}

// ---- account deletion ----

test("a caller under a deletion marker can't read, list, create or send; the other student still reads", async () => {
  await seedFriendship("u1", "u2");
  await seedFriendship("u1", "u3");
  await seedChat("u1", "u2");
  await seedMessage("u1~u2", "m1", "u1");
  await assertSucceeds(getDocs(messagesOf(studentDb("u1"), "u1~u2")));
  await seedDeletedAccount("u1");
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, "chats/u1~u2")));
  await assertFails(getDocs(myChats(db, "u1")));
  await assertFails(getDoc(doc(db, "chats/u1~u2/messages/m1")));
  await assertFails(getDocs(messagesOf(db, "u1~u2")));
  await assertFails(getDocs(latestOf(db, "u1~u2")));
  await assertFails(sendAs(db, "u1~u2", newMessage("u1")));
  // The friendship with u3 is still stored (deleteAccount hasn't reached it): no new chat either.
  await assertFails(sendAs(db, "u1~u3", newMessage("u1")));
  await expectMissing("chats/u1~u2/messages/new");
  await expectMissing("chats/u1~u3");
  const other = studentDb("u2");
  await assertSucceeds(getDoc(doc(other, "chats/u1~u2")));
  expectEqual(ids(await assertSucceeds(getDocs(myChats(other, "u2")))), "u1~u2", "listed");
  expectEqual(ids(await assertSucceeds(getDocs(messagesOf(other, "u1~u2")))), "m1", "history");
});

test("once deleteAccount has finished, the other student keeps a read-only chat with an anonymous author", async () => {
  // What deleteAccount leaves for u1's deletion: the marker, no friendship, senderId "" on
  // u1's messages, and u1 taken out of uids.
  await seedDeletedAccount("u1");
  await seedChat("u1", "u2", ["u2"]);
  await seedMessage("u1~u2", "theirs", "", 1_790_000_000_000);
  await seedMessage("u1~u2", "mine", "u2", 1_790_000_060_000);
  const other = studentDb("u2");
  expectEqual(ids(await assertSucceeds(getDocs(myChats(other, "u2")))), "u1~u2", "listed");
  const chat = await assertSucceeds(getDoc(doc(other, "chats/u1~u2")));
  expectEqual(chat.data(), { uids: ["u2"] }, "chat");
  const history = await assertSucceeds(getDocs(messagesOf(other, "u1~u2")));
  expectEqual(history.docs.map((d) => d.data().senderId), ["", "u2"], "authors");
  await assertFails(sendAs(other, "u1~u2", newMessage("u2")));
  await assertFails(updateDoc(doc(other, "chats/u1~u2/messages/theirs"), { senderId: "u1" }));
  await assertFails(updateDoc(doc(other, "chats/u1~u2"), { uids: ["u1", "u2"] }));
  await assertFails(deleteDoc(doc(other, "chats/u1~u2")));
  // The deleted account's token, valid up to an hour, reads nothing and no longer lists the chat.
  await assertFails(getDocs(myChats(studentDb("u1"), "u1")));
  await assertFails(getDocs(messagesOf(studentDb("u1"), "u1~u2")));
});

test("while deleteAccount hasn't deleted the friendship yet, the other student can still send", async () => {
  await seedFriendship("u1", "u2");
  await seedChat("u1", "u2");
  await seedDeletedAccount("u1");
  await assertSucceeds(sendAs(studentDb("u2"), "u1~u2", newMessage("u2")));
});

test("another student's deletion marker doesn't stop two friends chatting", async () => {
  await seedFriendship("u1", "u2");
  await seedDeletedAccount("u3");
  await assertSucceeds(sendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
});

// ---- the friendship rules stay as #338 says ----

test("the chat rules give nobody a friendship: a student can't accept their own request to chat", async () => {
  await assertSucceeds(requestAs("u1", "u2"));
  await assertFails(acceptAs("u1", "u2"));
  await assertFails(sendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
});
