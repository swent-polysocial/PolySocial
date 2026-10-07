// Contributors: Claude (rules tests for #33; also firebase/package.json, which can't hold a comment); Claude Opus 5.5 (testing agent: added edge-case, operation and path coverage tests, #33); Claude Opus 5.5 (users/{uid} tests and no catch-all, #35); Claude Opus 5.5 (testing agent: batches, transactions, queries, flag edge cases, uid paths, other paths, #35); Claude Opus 5.5 (fixed uid and accountType, #35). OpenAI Codex (event visibility and query tests, #52).
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
    await setDoc(doc(db, "groups/g1"), { title: "Seeded" });
    await setDoc(doc(db, "groups/g1/messages/m1"), { text: "Seeded" });
    for (const [id, event] of Object.entries({
      e1: { isPrivate: false, createdBy: "u2", allowedUids: ["u2"] },
      private: { isPrivate: true, createdBy: "u2", allowedUids: ["u2", "u3", "u4"] },
      clubPublic: {
        isPrivate: false,
        createdBy: "verifiedClub",
        allowedUids: ["verifiedClub"],
        isAssociationEvent: true,
      },
      clubPrivate: {
        isPrivate: true,
        createdBy: "verifiedClub",
        allowedUids: ["verifiedClub"],
        isAssociationEvent: true,
      },
    })) {
      await setDoc(doc(db, `events/${id}`), event);
    }
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
 * Every kind of access to a collection no rule covers yet, on a
 * top-level collection and on a subcollection. Since #35 there is no catch-all rule, so all of
 * them are denied. Each entry is run on its own so a test names the access that broke.
 */
const ACCESSES = {
  get: (db) => getDoc(doc(db, "groups/g1")),
  list: (db) => getDocs(collection(db, "groups")),
  create: (db) => setDoc(doc(db, "groups/g2"), { title: "New" }),
  update: (db) => updateDoc(doc(db, "groups/g1"), { title: "Changed" }),
  delete: (db) => deleteDoc(doc(db, "groups/g1")),
  "subcollection get": (db) => getDoc(doc(db, "groups/g1/messages/m1")),
  "subcollection create": (db) => setDoc(doc(db, "groups/g1/messages/m2"), { text: "Hi" }),
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

test("a verified @epfl.ch user is denied every access to uncovered collections", async () => {
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
    setDoc(doc(associationDb("newClub"), "users/newClub"), {
      uid: "newClub",
      accountType: "association",
    }),
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
  await assertFails(
    setDoc(doc(firestoreAs("student@epfl.ch", false, "u3"), "users/u3"), { uid: "u3" }),
  );
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
      uid: "newClub",
      accountType: "association",
      isAssociationVerified: false,
    }),
  );
});

test("creating your own users/{uid} with isAssociationVerified set is denied", async () => {
  for (const value of [true, "true", 1]) {
    await assertFails(
      setDoc(doc(associationDb("newClub"), "users/newClub"), {
        uid: "newClub",
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
  await assertFails(
    setDoc(doc(db, "users/verifiedClub"), { uid: "verifiedClub", accountType: "association" }),
  );
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

test("creating your own users/{uid} needs a uid field naming you", async () => {
  const db = studentDb("u3");

  await assertFails(setDoc(doc(db, "users/u3"), { uid: "u2", section: "IN" }));
  await assertFails(setDoc(doc(db, "users/u3"), { section: "IN" }));
  await assertFails(setDoc(doc(db, "users/u3"), { uid: "U3" }));
  if ((await storedUser("u3")) !== undefined) throw new Error("users/u3 was created");
});

test("updating your own users/{uid} can't change its uid", async () => {
  const db = studentDb("u1");

  await assertFails(updateDoc(doc(db, "users/u1"), { uid: "u2" }));
  await assertFails(updateDoc(doc(db, "users/u1"), { uid: deleteField() }));
  await assertFails(setDoc(doc(db, "users/u1"), { uid: "u2", displayName: "Student One" }));
});

test("updating your own users/{uid} can't change its accountType", async () => {
  await assertFails(
    updateDoc(doc(studentDb("u1"), "users/u1"), { accountType: "association" }),
  );
  const club = associationDb("club");
  await assertFails(updateDoc(doc(club, "users/club"), { accountType: "student" }));
  await assertFails(updateDoc(doc(club, "users/club"), { accountType: deleteField() }));
  await assertFails(
    setDoc(doc(club, "users/club"), {
      uid: "club",
      accountType: "student",
      isAssociationVerified: false,
    }),
  );
  if ((await storedUser("club")).accountType !== "association") {
    throw new Error("users/club's accountType changed");
  }
});

test("updating your own users/{uid} may keep its uid and accountType", async () => {
  const db = associationDb("club");

  await assertSucceeds(
    updateDoc(doc(db, "users/club"), { uid: "club", accountType: "association", section: "IN" }),
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
    setDoc(doc(db, "users/club"), {
      uid: "club",
      accountType: "association",
      isAssociationVerified: true,
    }),
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
      { uid: "newClub", isAssociationVerified: true },
      { merge: true },
    ),
  );
});

test("isAssociationVerified can't be set to null, on create or on update", async () => {
  await assertFails(
    setDoc(doc(associationDb("newClub"), "users/newClub"), {
      uid: "newClub",
      isAssociationVerified: null,
    }),
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

// ---- events/{id} read access (#52); writes remain denied until #47 ----

test("any verified EPFL student can get a public event", async () => {
  const snapshot = await assertSucceeds(getDoc(doc(studentDb("u1"), "events/e1")));
  if (snapshot.data().isPrivate !== false) throw new Error("Expected the public event");
});

for (const [role, uid] of [["creator", "u2"], ["member", "u3"], ["approved requester", "u4"]]) {
  test(`a private event's ${role} can read it through allowedUids`, async () => {
    await assertSucceeds(getDoc(doc(studentDb(uid), "events/private")));
  });
}

test("an unrelated student can't read a private event", async () => {
  await assertFails(getDoc(doc(studentDb("u1"), "events/private")));
});

test("a creator omitted from allowedUids can't bypass the private allowlist", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "events/omittedCreator"), {
      isPrivate: true, createdBy: "u1", organizerIds: ["u1"], allowedUids: ["u2"],
    });
  });
  await assertFails(getDoc(doc(studentDb("u1"), "events/omittedCreator")));
});

test("the public query explicitly filters out private events", async () => {
  const db = studentDb();
  const snapshot = await assertSucceeds(
    getDocs(query(collection(db, "events"), where("isPrivate", "==", false))),
  );
  if (snapshot.docs.map((item) => item.id).sort().join(",") !== "clubPublic,e1") {
    throw new Error("The public query returned unexpected events");
  }
  await assertFails(getDocs(collection(db, "events")));
  await assertFails(getDocs(query(collection(db, "events"), where("isPrivate", "==", true))));
});

test("the private query requires the current student's allowedUids membership", async () => {
  const db = studentDb("u3");
  const snapshot = await assertSucceeds(getDocs(query(
    collection(db, "events"), where("isPrivate", "==", true),
    where("allowedUids", "array-contains", "u3"),
  )));
  if (snapshot.docs.map((item) => item.id).join(",") !== "private") {
    throw new Error("The private query returned unexpected events");
  }
  await assertFails(getDocs(query(
    collection(db, "events"), where("isPrivate", "==", true),
    where("allowedUids", "array-contains", "u2"),
  )));
});

for (const [name, getDb] of [
  ["signed-out account", () => env.unauthenticatedContext().firestore()],
  ["unverified EPFL account", () => firestoreAs("student@epfl.ch", false, "u2")],
  ["verified outside account", () => firestoreAs("student@example.org", true, "u2")],
  ["unverified association", () => associationDb("club")],
  ["association without verified email", () => firestoreAs("contact@club.org", false, "verifiedClub")],
  ["account without email", () => env.authenticatedContext("u2", { email_verified: true }).firestore()],
  ["account without email verification", () => env.authenticatedContext("u2", { email: "student@epfl.ch" }).firestore()],
]) {
  test(`a ${name} can't get or query public or private events`, async () => {
    const db = getDb();
    for (const id of ["e1", "private", "clubPublic", "clubPrivate"]) {
      await assertFails(getDoc(doc(db, `events/${id}`)));
    }
    await assertFails(getDocs(query(collection(db, "events"), where("isPrivate", "==", false))));
    await assertFails(getDocs(query(
      collection(db, "events"), where("isPrivate", "==", true),
      where("allowedUids", "array-contains", "u2"),
    )));
  });
}

for (const [name, event] of [
  ["missing visibility", { allowedUids: ["u1"] }],
  ["string visibility", { isPrivate: "false", allowedUids: ["u1"] }],
  ["null visibility", { isPrivate: null, allowedUids: ["u1"] }],
  ["missing allowlist", { isPrivate: true }],
  ["map allowlist", { isPrivate: true, allowedUids: { u1: true } }],
  ["string allowlist", { isPrivate: true, allowedUids: "u1" }],
  ["empty allowlist", { isPrivate: true, allowedUids: [] }],
]) {
  test(`a student can't read an event with ${name}`, async () => {
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), "events/malformed"), event);
    });
    await assertFails(getDoc(doc(studentDb(), "events/malformed")));
  });
}

test("a verified association can only read its own association events", async () => {
  const db = associationDb("verifiedClub");
  await assertSucceeds(getDoc(doc(db, "events/clubPublic")));
  await assertSucceeds(getDoc(doc(db, "events/clubPrivate")));
  await assertFails(getDoc(doc(db, "events/e1")));
  await assertFails(getDoc(doc(db, "events/private")));
  await assertFails(getDoc(doc(db, "users/u1")));
  await assertFails(getDoc(doc(db, "groups/g1")));
  await assertFails(getDocs(query(collection(db, "events"), where("isPrivate", "==", false))));
  const snapshot = await assertSucceeds(getDocs(query(
    collection(db, "events"), where("createdBy", "==", "verifiedClub"),
    where("isAssociationEvent", "==", true),
  )));
  if (snapshot.size !== 2) throw new Error("Expected the association's two own events");
});

test("allowlist membership alone gives an outside association no student event access", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "events/private"), {
      allowedUids: ["u2", "verifiedClub"],
    });
  });
  await assertFails(getDoc(doc(associationDb("verifiedClub"), "events/private")));
});

test("a client can't grant association access through token claims", async () => {
  const db = env.authenticatedContext("club", {
    email: "contact@club.org", email_verified: true,
    accountType: "association", isAssociationVerified: true,
  }).firestore();
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "events/unverifiedClub"), {
      isPrivate: false, createdBy: "club", isAssociationEvent: true,
    });
  });
  await assertFails(getDoc(doc(db, "events/unverifiedClub")));
});

for (const [name, profile] of [
  ["string verification flag", { accountType: "association", isAssociationVerified: "true" }],
  ["wrong account type", { accountType: "student", isAssociationVerified: true }],
  ["missing verification flag", { accountType: "association" }],
]) {
  test(`association access denies a profile with ${name}`, async () => {
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), "users/verifiedClub"), profile);
    });
    await assertFails(getDoc(doc(associationDb("verifiedClub"), "events/clubPublic")));
  });
}

test("association ownership also requires the association-event badge", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "events/clubPublic"), { isAssociationEvent: false });
  });
  await assertFails(getDoc(doc(associationDb("verifiedClub"), "events/clubPublic")));
});

test("read access doesn't authorize event writes or event subcollections", async () => {
  for (const db of [studentDb("u2"), associationDb("verifiedClub")]) {
    await assertFails(setDoc(doc(db, "events/new"), { isPrivate: false }));
    await assertFails(updateDoc(doc(db, "events/e1"), { isPrivate: false }));
    await assertFails(deleteDoc(doc(db, "events/e1")));
    await assertFails(getDoc(doc(db, "events/e1/messages/m1")));
    await assertFails(setDoc(doc(db, "events/e1/messages/m2"), { text: "Hi" }));
  }
});
