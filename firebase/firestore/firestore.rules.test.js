// Contributors: Claude (rules tests for #33; also firebase/package.json, which can't hold a comment); Claude Opus 5.5 (testing agent: added edge-case, operation and path coverage tests; users/{uid} tests and no catch-all, #35); Claude Opus 5.5 (testing agent: batches, transactions, queries, flag edge cases, uid paths, other paths, #35).
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
  deleteField,
  doc,
  documentId,
  getDoc,
  getDocs,
  query,
  runTransaction,
  setDoc,
  updateDoc,
  where,
  writeBatch,
} = require("firebase/firestore");

// A "demo-" project never reaches a real Firebase project.
const PROJECT_ID = "demo-polysocial";

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(path.join(__dirname, "firestore.rules"), "utf8") },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, "events/e1"), { title: "Seeded" });
    await setDoc(doc(db, "events/e1/messages/m1"), { text: "Seeded" });
    await setDoc(doc(db, "users/u1"), { uid: "u1", displayName: "Student One" });
    await setDoc(doc(db, "users/u2"), { uid: "u2", displayName: "Student Two" });
    await setDoc(doc(db, "users/club"), {
      uid: "club",
      accountType: "association",
      isAssociationVerified: false,
    });
    await setDoc(doc(db, "users/verifiedClub"), {
      uid: "verifiedClub",
      accountType: "association",
      isAssociationVerified: true,
    });
  });
});

after(async () => {
  await env.cleanup();
});

/** Firestore as the signed-in user [uid] with the given email claims. */
function firestoreAs(email, emailVerified, uid = "u1") {
  return env
    .authenticatedContext(uid, { email, email_verified: emailVerified })
    .firestore();
}

/**
 * Every kind of access to a collection no rule covers yet (#47 adds the events rules), on a
 * top-level collection and on a subcollection. Since #35 there is no catch-all rule, so all of
 * them are denied. Each entry is run on its own so a test names the access that broke.
 */
const ACCESSES = {
  get: (db) => getDoc(doc(db, "events/e1")),
  list: (db) => getDocs(collection(db, "events")),
  create: (db) => setDoc(doc(db, "events/e2"), { title: "New" }),
  update: (db) => updateDoc(doc(db, "events/e1"), { title: "Changed" }),
  delete: (db) => deleteDoc(doc(db, "events/e1")),
  "subcollection get": (db) => getDoc(doc(db, "events/e1/messages/m1")),
  "subcollection create": (db) => setDoc(doc(db, "events/e1/messages/m2"), { text: "Hi" }),
};

async function assertAllAccessDenied(db) {
  for (const [name, access] of Object.entries(ACCESSES)) {
    await assertFails(access(db)).catch((error) => {
      throw new Error(`${name} was allowed: ${error.message}`);
    });
  }
}

test("an unverified @epfl.ch user can't read or write", async () => {
  const db = firestoreAs("student@epfl.ch", false);

  await assertFails(getDoc(doc(db, "events/e1")));
  await assertFails(setDoc(doc(db, "events/e2"), { title: "New" }));
});

test("a verified non-EPFL user can't read or write", async () => {
  const db = firestoreAs("student@gmail.com", true);

  await assertFails(getDoc(doc(db, "events/e1")));
  await assertFails(setDoc(doc(db, "events/e2"), { title: "New" }));
});

test("a verified @epfl.ch user is denied every access outside users/{uid}", async () => {
  await assertAllAccessDenied(firestoreAs("student@epfl.ch", true));
});

test("a signed-out user is denied every access", async () => {
  await assertAllAccessDenied(env.unauthenticatedContext().firestore());
});

test("an unverified @epfl.ch user is denied every access", async () => {
  await assertAllAccessDenied(firestoreAs("student@epfl.ch", false));
});

test("a verified non-EPFL user is denied every access", async () => {
  await assertAllAccessDenied(firestoreAs("student@gmail.com", true));
});

test("a verified user without an email claim is denied", async () => {
  const db = env.authenticatedContext("u1", { email_verified: true }).firestore();

  await assertAllAccessDenied(db);
});

test("an @epfl.ch user without an email_verified claim is denied", async () => {
  const db = env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore();

  await assertAllAccessDenied(db);
});

test('an @epfl.ch user whose email_verified is the string "true" is denied', async () => {
  await assertAllAccessDenied(firestoreAs("student@epfl.ch", "true"));
});

// Emails that are not exactly <name>@epfl.ch. Subdomains and an uppercase domain stay denied
// for now (see "Open questions" in CONTEXT.md).
for (const email of [
  "student@epfl.ch.evil.com",
  "student@notepfl.ch",
  "epfl.ch@gmail.com",
  "student@epflXch",
  "@epfl.ch",
  "a@b@epfl.ch",
  "student@sub.epfl.ch",
  "student@EPFL.CH",
]) {
  test(`a verified look-alike email (${email}) is denied`, async () => {
    await assertAllAccessDenied(firestoreAs(email, true));
  });
}

// ---- users/{uid} (#35) ----

/** A verified @epfl.ch student signed in as [uid]. */
function studentDb(uid = "u1") {
  return firestoreAs("student@epfl.ch", true, uid);
}

/** A verified non-EPFL account (an association) signed in as [uid]. */
function associationDb(uid) {
  return firestoreAs("contact@club.org", true, uid);
}

test("a student can read, update and delete their own users/{uid}", async () => {
  const db = studentDb("u1");

  await assertSucceeds(getDoc(doc(db, "users/u1")));
  await assertSucceeds(updateDoc(doc(db, "users/u1"), { section: "IN" }));
  await assertSucceeds(deleteDoc(doc(db, "users/u1")));
});

test("a student can read their own users/{uid} before it exists", async () => {
  // The profile step's create-once transaction reads the document first (#34).
  const snapshot = await assertSucceeds(getDoc(doc(studentDb("u3"), "users/u3")));
  if (snapshot.exists()) throw new Error("users/u3 should not exist yet");
});

test("a student can't read another student's users/{uid} that doesn't exist", async () => {
  await assertFails(getDoc(doc(studentDb("u1"), "users/u9")));
});

test("a student can create their own users/{uid}", async () => {
  await assertSucceeds(setDoc(doc(studentDb("u3"), "users/u3"), { uid: "u3", section: "IN" }));
});

test("an association account with a non-EPFL verified email can read and write its own users/{uid}", async () => {
  const db = associationDb("club");

  await assertSucceeds(getDoc(doc(db, "users/club")));
  await assertSucceeds(updateDoc(doc(db, "users/club"), { displayName: "Club" }));
  await assertSucceeds(
    setDoc(doc(associationDb("newClub"), "users/newClub"), { accountType: "association" }),
  );
});

test("a student can't read or write another student's users/{uid}", async () => {
  const db = studentDb("u1");

  await assertFails(getDoc(doc(db, "users/u2")));
  await assertFails(updateDoc(doc(db, "users/u2"), { section: "IN" }));
  await assertFails(setDoc(doc(db, "users/u2"), { uid: "u2" }));
  await assertFails(deleteDoc(doc(db, "users/u2")));
  await assertFails(setDoc(doc(db, "users/u9"), { uid: "u9" }));
});

test("an association account can't read or write a student's users/{uid}", async () => {
  const db = associationDb("verifiedClub");

  await assertFails(getDoc(doc(db, "users/u1")));
  await assertFails(updateDoc(doc(db, "users/u1"), { section: "IN" }));
});

test("no one can list the users collection", async () => {
  await assertFails(getDocs(collection(studentDb("u1"), "users")));
});

test("an unverified account can't read or write its own users/{uid}", async () => {
  for (const db of [
    firestoreAs("student@epfl.ch", false, "u1"),
    firestoreAs("contact@club.org", false, "club"),
  ]) {
    await assertFails(getDoc(doc(db, "users/u1")));
    await assertFails(getDoc(doc(db, "users/club")));
    await assertFails(updateDoc(doc(db, "users/u1"), { section: "IN" }));
    await assertFails(updateDoc(doc(db, "users/club"), { displayName: "Club" }));
  }
  await assertFails(setDoc(doc(firestoreAs("student@epfl.ch", false, "u3"), "users/u3"), {}));
});

test('an account whose email_verified is the string "true" can\'t reach its own users/{uid}', async () => {
  await assertFails(getDoc(doc(firestoreAs("student@epfl.ch", "true", "u1"), "users/u1")));
});

test("a signed-out user can't read or write any users/{uid}", async () => {
  const db = env.unauthenticatedContext().firestore();

  await assertFails(getDoc(doc(db, "users/u1")));
  await assertFails(setDoc(doc(db, "users/u3"), { uid: "u3" }));
});

test("a subcollection of your own users/{uid} is denied", async () => {
  await assertFails(setDoc(doc(studentDb("u1"), "users/u1/private/x"), { text: "Hi" }));
});

test("creating your own users/{uid} with isAssociationVerified false is allowed", async () => {
  await assertSucceeds(
    setDoc(doc(associationDb("newClub"), "users/newClub"), {
      accountType: "association",
      isAssociationVerified: false,
    }),
  );
});

test("creating your own users/{uid} with isAssociationVerified set is denied", async () => {
  for (const value of [true, "true", 1]) {
    await assertFails(
      setDoc(doc(associationDb("newClub"), "users/newClub"), {
        accountType: "association",
        isAssociationVerified: value,
      }),
    ).catch((error) => {
      throw new Error(`isAssociationVerified ${JSON.stringify(value)} was allowed: ${error.message}`);
    });
  }
});

test("updating your own users/{uid} can't set isAssociationVerified", async () => {
  await assertFails(
    updateDoc(doc(associationDb("club"), "users/club"), { isAssociationVerified: true }),
  );
  await assertFails(
    updateDoc(doc(studentDb("u1"), "users/u1"), { isAssociationVerified: false }),
  );
});

test("a verified association can't change or remove its isAssociationVerified", async () => {
  const db = associationDb("verifiedClub");

  await assertFails(updateDoc(doc(db, "users/verifiedClub"), { isAssociationVerified: false }));
  await assertFails(updateDoc(doc(db, "users/verifiedClub"), { isAssociationVerified: deleteField() }));
  await assertFails(setDoc(doc(db, "users/verifiedClub"), { accountType: "association" }));
});

test("a verified association can update its other fields, keeping isAssociationVerified", async () => {
  const db = associationDb("verifiedClub");

  await assertSucceeds(updateDoc(doc(db, "users/verifiedClub"), { displayName: "Club" }));
  await assertSucceeds(
    setDoc(doc(db, "users/verifiedClub"), {
      uid: "verifiedClub",
      accountType: "association",
      isAssociationVerified: true,
      displayName: "Club",
    }),
  );
});

// ---- users/{uid}: attacks beyond a single get or write (#35) ----

/** The stored users/[uid] document, read with the rules off, or undefined if it doesn't exist. */
async function storedUser(uid) {
  let data;
  await env.withSecurityRulesDisabled(async (context) => {
    data = (await getDoc(doc(context.firestore(), `users/${uid}`))).data();
  });
  return data;
}

test("a batched write touching another student's users/{uid} is denied as a whole", async () => {
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u1"), { section: "IN" });
  batch.update(doc(db, "users/u2"), { section: "IN" });

  await assertFails(batch.commit());
  if ((await storedUser("u1")).section !== undefined) throw new Error("the batch changed users/u1");
  if ((await storedUser("u2")).section !== undefined) throw new Error("the batch changed users/u2");
});

test("a transaction can create your own users/{uid}, not read or write another's", async () => {
  const db = studentDb("u3");

  // The profile step's create-once transaction (#34).
  await assertSucceeds(
    runTransaction(db, async (transaction) => {
      const own = doc(db, "users/u3");
      if (!(await transaction.get(own)).exists()) transaction.set(own, { uid: "u3" });
    }),
  );
  await assertFails(runTransaction(db, (transaction) => transaction.get(doc(db, "users/u2"))));
  await assertFails(
    runTransaction(db, async (transaction) => {
      await transaction.get(doc(db, "users/u3"));
      transaction.update(doc(db, "users/u2"), { section: "IN" });
    }),
  );
});

test("a users query is allowed by your own document ID only, not by a field", async () => {
  const db = studentDb("u1");

  // Rules aren't filters: a query is allowed only if it can return nothing but your own document.
  await assertSucceeds(getDocs(query(collection(db, "users"), where(documentId(), "==", "u1"))));
  await assertFails(getDocs(query(collection(db, "users"), where(documentId(), "==", "u2"))));
  await assertFails(getDocs(query(collection(db, "users"), where("uid", "==", "u1"))));
  await assertFails(getDocs(collectionGroup(db, "users")));
});

test("recreating your own users/{uid} can't set isAssociationVerified", async () => {
  const db = associationDb("club");

  await assertSucceeds(deleteDoc(doc(db, "users/club")));
  await assertFails(
    setDoc(doc(db, "users/club"), { accountType: "association", isAssociationVerified: true }),
  );
  if ((await storedUser("club")) !== undefined) throw new Error("users/club was recreated");
});

test("a merge write can't set isAssociationVerified on your own users/{uid}", async () => {
  // On an existing document (an update) and on a missing one (a create).
  await assertFails(
    setDoc(
      doc(associationDb("club"), "users/club"),
      { isAssociationVerified: true },
      { merge: true },
    ),
  );
  await assertFails(
    setDoc(
      doc(associationDb("newClub"), "users/newClub"),
      { isAssociationVerified: true },
      { merge: true },
    ),
  );
});

test("isAssociationVerified can't be set to null, on create or on update", async () => {
  await assertFails(
    setDoc(doc(associationDb("newClub"), "users/newClub"), { isAssociationVerified: null }),
  );
  await assertFails(updateDoc(doc(studentDb("u1"), "users/u1"), { isAssociationVerified: null }));
  await assertFails(
    updateDoc(doc(associationDb("club"), "users/club"), { isAssociationVerified: null }),
  );
});

test("an unverified association can't set isAssociationVerified by overwriting", async () => {
  await assertFails(
    setDoc(doc(associationDb("club"), "users/club"), {
      uid: "club",
      accountType: "association",
      isAssociationVerified: true,
    }),
  );
  if ((await storedUser("club")).isAssociationVerified !== false) {
    throw new Error("users/club's isAssociationVerified changed");
  }
});

test("users/{uid} matches the uid exactly (case and whitespace)", async () => {
  await assertFails(setDoc(doc(studentDb("u1"), "users/U1"), { uid: "U1" }));
  await assertFails(setDoc(doc(studentDb("u1"), "users/u1 "), { uid: "u1 " }));
  await assertFails(getDoc(doc(studentDb("U1"), "users/u1")));
});

test("a long uid reaches its own users/{uid}, not a shared prefix", async () => {
  // Firebase uids are at most 128 characters.
  const uid = "a".repeat(127) + "Z";
  const db = studentDb(uid);

  await assertSucceeds(setDoc(doc(db, `users/${uid}`), { uid }));
  await assertSucceeds(getDoc(doc(db, `users/${uid}`)));
  await assertFails(setDoc(doc(db, `users/${uid.slice(0, -1)}`), { uid }));
});

test("an account without an email_verified claim can't reach its own users/{uid}", async () => {
  const db = env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore();

  await assertFails(getDoc(doc(db, "users/u1")));
  await assertFails(updateDoc(doc(db, "users/u1"), { section: "IN" }));
  await assertFails(deleteDoc(doc(db, "users/u1")));
});

// Collections and paths no rule covers yet, including look-alikes of users/{uid} and paths that
// end in the caller's own uid. Only users/{uid} itself is reachable.
const OTHER_PATHS = [
  "publicProfiles/u1",
  "associations/u1",
  "users_backup/u1",
  "Users/u1",
  "events/e1/users/u1",
  "users/u1/private/x/deeper/y",
];

for (const [name, db] of [
  ["a verified student", () => studentDb("u1")],
  ["a verified association", () => associationDb("u1")],
  ["an unverified student", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't read or write other collections or deeper paths`, async () => {
    for (const p of OTHER_PATHS) {
      await assertFails(getDoc(doc(db(), p))).catch((error) => {
        throw new Error(`get ${p} was allowed: ${error.message}`);
      });
      await assertFails(setDoc(doc(db(), p), { uid: "u1" })).catch((error) => {
        throw new Error(`create ${p} was allowed: ${error.message}`);
      });
    }
  });
}
