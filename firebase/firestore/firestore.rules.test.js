// Contributors: Claude (rules tests for #33, and firebase/package.json, which can't hold a comment; edge-case, operation and path coverage, #33; users/{uid} tests, no catch-all, batches, transactions, queries, flag edge cases, uid paths, other paths, and fixed uid and accountType, #35; @epfl.ch association tests, accountType values on create and association profile deletes, #161; associations/{uid} tests, #126, with more from a test audit and a review; displayName limit, written test-first, #143; publicProfiles/{uid} tests, written test-first, #229; reads by ID only and the fixed section and year lists, after review, #229; private edits keep the public copy, and deleted accounts stay deleted, after review, #229; interests from the fixed list, test-first, #230; any code read in the list check, after review, #230); OpenAI Codex (event visibility and query tests, #52); Claude Opus 5.5 (event write tests, #47; badge, locationName, profile-delete and organizer-reader tests after review; registration tests, #121; deleted accounts can't register, #275); Claude (Opus 5.5, Anthropic) (event group and groupMembers marker tests, #232, with more after review; group chat message tests, #237, and a deleted account's messages, after review).
// Contributors: Claude (Opus 5.5, Anthropic) (a non-EPFL association in allowedUids can't run My events' private query, #320).
const { after, before, beforeEach, test } = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { appInterests, appProfileOptions } = require("../appProfileOptions");
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
  limit,
  orderBy,
  query,
  runTransaction,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
  GeoPoint,
  Timestamp,
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

/** A start time far enough ahead that the seeded events never end while the tests run. */
const FAR_FUTURE = Timestamp.fromDate(new Date("2099-01-01T18:00:00Z"));

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, "groups/g1"), { title: "Seeded" });
    await setDoc(doc(db, "groups/g1/messages/m1"), { text: "Seeded" });
    for (const [id, event] of Object.entries({
      e1: { isPrivate: false, createdBy: "u2", allowedUids: ["u2"], startTime: FAR_FUTURE },
      private: {
        isPrivate: true,
        createdBy: "u2",
        allowedUids: ["u2", "u3", "u4"],
        startTime: FAR_FUTURE,
      },
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

/** Deletes users/[uid] with the rules off, as an admin deletes an association's account. */
async function deleteUserAsAdmin(uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    await deleteDoc(doc(context.firestore(), `users/${uid}`));
  });
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

test("an association can't delete its own users/{uid}", async () => {
  for (const uid of ["club", "verifiedClub"]) {
    await assertFails(deleteDoc(doc(associationDb(uid), `users/${uid}`)));
    if ((await storedUser(uid)) === undefined) throw new Error(`users/${uid} was deleted`);
  }
});

test("a student can delete their own users/{uid} even before it exists", async () => {
  await assertSucceeds(deleteDoc(doc(studentDb("u3"), "users/u3")));
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

test("creating your own users/{uid} as a student or an association is allowed", async () => {
  await assertSucceeds(
    setDoc(doc(studentDb("u3"), "users/u3"), { uid: "u3", accountType: "student" }),
  );
  await assertSucceeds(
    setDoc(doc(associationDb("newClub"), "users/newClub"), {
      uid: "newClub",
      accountType: "association",
    }),
  );
});

// Any other value would count as a student in isStudent(), which only checks for "association"
// (#161).
for (const [name, accountType] of [
  ['"Association"', "Association"],
  ['"admin"', "admin"],
  ['""', ""],
  ["true", true],
  ['["association"]', ["association"]],
]) {
  test(`creating your own users/{uid} with accountType ${name} is denied`, async () => {
    const db = studentDb("u3");

    await assertFails(setDoc(doc(db, "users/u3"), { uid: "u3", accountType }));
    await assertFails(setDoc(doc(db, "users/u3"), { uid: "u3", accountType }, { merge: true }));
    if ((await storedUser("u3")) !== undefined) throw new Error("users/u3 was created");
  });
}

test("recreating your own users/{uid} needs a valid accountType too", async () => {
  const db = associationDb("club");

  await deleteUserAsAdmin("club");
  await assertFails(setDoc(doc(db, "users/club"), { uid: "club", accountType: "Association" }));
  await assertFails(
    setDoc(doc(db, "users/club"), { uid: "club", accountType: "admin" }, { merge: true }),
  );
  await assertSucceeds(setDoc(doc(db, "users/club"), { uid: "club", accountType: "association" }));
});

// ---- users/{uid}: the full name's limit (#143) ----
// At most 50 characters (the app's MAX_DISPLAY_NAME_LENGTH). The rule's size() counts UTF-16 units,
// as the app does: an accented letter is 1, an emoji is 2 (pinned below).

const NAME_AT_LIMIT = "a".repeat(50);
const NAME_OVER_LIMIT = "a".repeat(51);
const EMOJI = "\u{1F600}"; // 😀: one character, two UTF-16 units

async function storeUserAsAdmin(uid, data) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `users/${uid}`), data);
  });
}

test("creating your own users/{uid} with a displayName at the limit is allowed", async () => {
  await assertSucceeds(
    setDoc(doc(studentDb("u3"), "users/u3"), { uid: "u3", displayName: NAME_AT_LIMIT }),
  );
});

test("creating your own users/{uid} with a displayName over the limit is denied", async () => {
  const db = studentDb("u3");

  await assertFails(setDoc(doc(db, "users/u3"), { uid: "u3", displayName: NAME_OVER_LIMIT }));
  await assertFails(
    setDoc(doc(db, "users/u3"), { uid: "u3", displayName: NAME_OVER_LIMIT }, { merge: true }),
  );
  if ((await storedUser("u3")) !== undefined) throw new Error("users/u3 was created");
});

test("the displayName limit counts an emoji as 2: 25 are allowed, 26 are denied", async () => {
  const db = studentDb("u3");

  await assertFails(setDoc(doc(db, "users/u3"), { uid: "u3", displayName: EMOJI.repeat(26) }));
  await assertSucceeds(setDoc(doc(db, "users/u3"), { uid: "u3", displayName: EMOJI.repeat(25) }));
});

test("the displayName limit counts an accented letter as one character", async () => {
  const db = studentDb("u3");

  await assertFails(setDoc(doc(db, "users/u3"), { uid: "u3", displayName: "é".repeat(51) }));
  await assertSucceeds(setDoc(doc(db, "users/u3"), { uid: "u3", displayName: "é".repeat(50) }));
});

for (const [name, displayName] of [
  ["a number", 42],
  ["null", null],
  ["a list", [NAME_AT_LIMIT]],
  ["a map", { first: "Franek" }],
]) {
  test(`creating your own users/{uid} with ${name} as displayName is denied`, async () => {
    await assertFails(setDoc(doc(studentDb("u3"), "users/u3"), { uid: "u3", displayName }));
  });
}

test("creating your own users/{uid} without a displayName is still allowed", async () => {
  await assertSucceeds(setDoc(doc(studentDb("u3"), "users/u3"), { uid: "u3", section: "IN" }));
});

test("updating your own displayName to the limit is allowed, over it is denied", async () => {
  await storeUserAsAdmin("u3", { uid: "u3", displayName: "Short" });
  const db = studentDb("u3");

  await assertFails(updateDoc(doc(db, "users/u3"), { displayName: NAME_OVER_LIMIT }));
  await assertFails(
    setDoc(doc(db, "users/u3"), { displayName: NAME_OVER_LIMIT }, { merge: true }),
  );
  await assertFails(setDoc(doc(db, "users/u3"), { uid: "u3", displayName: NAME_OVER_LIMIT }));
  await assertSucceeds(updateDoc(doc(db, "users/u3"), { displayName: NAME_AT_LIMIT }));
  if ((await storedUser("u3")).displayName !== NAME_AT_LIMIT) throw new Error("not updated");
});

test("updating your own displayName to a non-string is denied", async () => {
  await storeUserAsAdmin("u3", { uid: "u3", displayName: "Short" });

  await assertFails(updateDoc(doc(studentDb("u3"), "users/u3"), { displayName: 42 }));
});

test("a batch can't slip a displayName over the limit into your own users/{uid}", async () => {
  await storeUserAsAdmin("u3", { uid: "u3", displayName: "Short" });
  const db = studentDb("u3");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u3"), { section: "IN" });
  batch.update(doc(db, "users/u3"), { displayName: NAME_OVER_LIMIT });

  await assertFails(batch.commit());
  if ((await storedUser("u3")).displayName !== "Short") throw new Error("the batch was applied");
});

test("a stored displayName over the limit must be shortened before any other update", async () => {
  // A document written before #143, or by an admin.
  await storeUserAsAdmin("u3", { uid: "u3", displayName: NAME_OVER_LIMIT });
  const db = studentDb("u3");

  await assertFails(updateDoc(doc(db, "users/u3"), { section: "IN" }));
  await assertSucceeds(updateDoc(doc(db, "users/u3"), { displayName: NAME_AT_LIMIT, section: "IN" }));
});

test("an association's displayName has the same limit", async () => {
  const db = associationDb("newClub");

  await assertFails(
    setDoc(doc(db, "users/newClub"), {
      uid: "newClub",
      accountType: "association",
      displayName: NAME_OVER_LIMIT,
    }),
  );
  await assertSucceeds(
    setDoc(doc(db, "users/newClub"), {
      uid: "newClub",
      accountType: "association",
      displayName: NAME_AT_LIMIT,
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

  await deleteUserAsAdmin("club");
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
// end in the caller's own uid. Only users/{uid} itself is reachable. publicProfiles/{uid} has its
// own tests since #229 (below).
const OTHER_PATHS = [
  "publicProfiles/u1/private/x",
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

// ---- events/{id} read access (#52) ----

test("the map's public upcoming query is allowed and keeps its sorted time window", async () => {
  const start = Timestamp.fromDate(new Date("2026-01-01T12:00:00Z"));
  const end = Timestamp.fromDate(new Date("2026-01-15T12:00:00Z"));
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    for (const [id, isPrivate, date] of [
      ["sooner", false, "2026-01-02T12:00:00Z"],
      ["later", false, "2026-01-14T12:00:00Z"],
      ["hidden", true, "2026-01-03T12:00:00Z"],
      ["started", false, "2026-01-01T11:59:59Z"],
      ["outside", false, "2026-01-15T12:00:00Z"],
    ]) {
      await setDoc(doc(db, `events/${id}`), {
        isPrivate, startTime: Timestamp.fromDate(new Date(date)), allowedUids: ["u2"],
      });
    }
  });
  const events = collection(studentDb("u1"), "events");
  const constraints = [
    where("startTime", ">=", start), where("startTime", "<", end), orderBy("startTime"),
  ];
  const snapshot = await assertSucceeds(getDocs(query(events, where("isPrivate", "==", false), ...constraints)));
  const ids = snapshot.docs.map((event) => event.id);
  if (JSON.stringify(ids) !== JSON.stringify(["sooner", "later"])) {
    throw new Error(`Unexpected public upcoming events: ${JSON.stringify(ids)}`);
  }
  await assertFails(getDocs(query(events, ...constraints)));
});

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

// My events (#320) runs exactly this query; the @epfl.ch associations are covered under #161 below.
test("an association listed in allowedUids can't run the students' private query", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "events/private"), {
      allowedUids: ["u2", "verifiedClub", "club"],
    });
  });
  for (const uid of ["verifiedClub", "club"]) {
    await assertFails(getDocs(query(
      collection(associationDb(uid), "events"), where("isPrivate", "==", true),
      where("allowedUids", "array-contains", uid),
    ))).catch((error) => {
      throw new Error(`${uid}'s private query was allowed: ${error.message}`);
    });
  }
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

// ---- @epfl.ch associations (#161) ----

/** An association account with an @epfl.ch email, signed in as [uid]. */
function epflAssociationDb(uid) {
  return firestoreAs("club@epfl.ch", true, uid);
}

/** Seeds users/[uid] as an association, verified or not, listed in events/private. */
async function seedEpflAssociation(uid, verified) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, `users/${uid}`), {
      uid, accountType: "association", isAssociationVerified: verified,
    });
    await updateDoc(doc(db, "events/private"), { allowedUids: ["u2", uid] });
    await setDoc(doc(db, `events/${uid}Event`), {
      isPrivate: false, createdBy: uid, allowedUids: [uid], isAssociationEvent: true,
    });
  });
}

for (const verified of [false, true]) {
  const kind = verified ? "a verified" : "an unverified";

  test(`${kind} @epfl.ch association gets no student access to events`, async () => {
    await seedEpflAssociation("epflClub", verified);
    const db = epflAssociationDb("epflClub");

    for (const id of ["e1", "private", "clubPublic"]) {
      await assertFails(getDoc(doc(db, `events/${id}`)));
    }
    await assertFails(getDocs(query(collection(db, "events"), where("isPrivate", "==", false))));
    await assertFails(getDocs(query(
      collection(db, "events"), where("isPrivate", "==", true),
      where("allowedUids", "array-contains", "epflClub"),
    )));
  });

  test(`${kind} @epfl.ch association can still use its own users/{uid}`, async () => {
    await seedEpflAssociation("epflClub", verified);
    const db = epflAssociationDb("epflClub");

    await assertSucceeds(getDoc(doc(db, "users/epflClub")));
    await assertSucceeds(updateDoc(doc(db, "users/epflClub"), { displayName: "Club" }));
  });

  test(`${kind} @epfl.ch association can't delete its profile to become a student`, async () => {
    await seedEpflAssociation("epflClub", verified);
    const db = epflAssociationDb("epflClub");

    await assertFails(deleteDoc(doc(db, "users/epflClub")));
    await assertFails(getDoc(doc(db, "events/e1")));
  });
}

test("an unverified @epfl.ch association can't read its own association events", async () => {
  await seedEpflAssociation("epflClub", false);

  await assertFails(getDoc(doc(epflAssociationDb("epflClub"), "events/epflClubEvent")));
});

test("a verified @epfl.ch association reads its own association events like any association", async () => {
  await seedEpflAssociation("epflClub", true);
  const db = epflAssociationDb("epflClub");

  await assertSucceeds(getDoc(doc(db, "events/epflClubEvent")));
  const snapshot = await assertSucceeds(getDocs(query(
    collection(db, "events"), where("createdBy", "==", "epflClub"),
    where("isAssociationEvent", "==", true),
  )));
  if (snapshot.docs.map((item) => item.id).join(",") !== "epflClubEvent") {
    throw new Error("Expected only the association's own event");
  }
});

for (const [name, profile] of [
  ["a student profile", { uid: "u5", accountType: "student" }],
  ["a profile without accountType", { uid: "u5" }],
  ["no profile yet", null],
]) {
  test(`an @epfl.ch student with ${name} keeps student access to events`, async () => {
    await env.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      if (profile) await setDoc(doc(db, "users/u5"), profile);
      await updateDoc(doc(db, "events/private"), { allowedUids: ["u2", "u5"] });
    });
    const db = studentDb("u5");

    await assertSucceeds(getDoc(doc(db, "events/e1")));
    await assertSucceeds(getDoc(doc(db, "events/private")));
    await assertSucceeds(getDocs(query(collection(db, "events"), where("isPrivate", "==", false))));
  });
}

test("read access doesn't authorize event writes or event subcollections", async () => {
  for (const db of [studentDb("u2"), associationDb("verifiedClub")]) {
    await assertFails(setDoc(doc(db, "events/new"), { isPrivate: false }));
    await assertFails(updateDoc(doc(db, "events/e1"), { isPrivate: false }));
    await assertFails(deleteDoc(doc(db, "events/e1")));
    await assertFails(getDoc(doc(db, "events/e1/messages/m1")));
    await assertFails(setDoc(doc(db, "events/e1/messages/m2"), { text: "Hi" }));
  }
});

// ---- events/{id} writes (#47) ----

/** An event as `Event.toFirestoreMap()` writes it, created by [uid], with [overrides]. */
function eventBy(uid, overrides = {}) {
  return {
    title: "Study session",
    description: "",
    category: "STUDY",
    location: new GeoPoint(46.5191, 6.5668),
    startTime: Timestamp.fromDate(new Date("2026-11-02T18:00:00Z")),
    endTime: null,
    capacity: null,
    isPrivate: false,
    createdBy: uid,
    organizerIds: [uid],
    allowedUids: [uid],
    isAssociationEvent: false,
    ...overrides,
  };
}

/** Stores [data] as events/[id], bypassing the rules. */
async function seedEvent(id, data) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `events/${id}`), data);
  });
}

/** u2 created "managed", u5 co-organizes it, and u3 may only read it. */
async function seedManagedEvent() {
  await seedEvent("managed", eventBy("u2", {
    organizerIds: ["u2", "u5"], allowedUids: ["u2", "u5", "u3"],
  }));
}

test("a verified student can create an event they created", async () => {
  await assertSucceeds(setDoc(doc(studentDb("u1"), "events/new"), eventBy("u1")));
});

test("a verified student can create an event in a transaction, as the app does", async () => {
  const db = studentDb("u1");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    transaction.set(doc(db, "events/new"), eventBy("u1", { isPrivate: true }));
  }));
});

test("a verified association can publish an event under its name and read it back", async () => {
  const db = associationDb("verifiedClub");
  await assertSucceeds(setDoc(
    doc(db, "events/clubNew"), eventBy("verifiedClub", { isAssociationEvent: true }),
  ));
  await assertSucceeds(getDoc(doc(db, "events/clubNew")));
});

// It could create it, but its own read rule (association events only) would then deny it.
test("a verified association can't create an event without its badge", async () => {
  await assertFails(setDoc(
    doc(associationDb("verifiedClub"), "events/clubPlain"), eventBy("verifiedClub"),
  ));
});

test("an event whose createdBy isn't the caller is denied", async () => {
  await assertFails(setDoc(doc(studentDb("u1"), "events/new"), eventBy("u2", {
    organizerIds: ["u1", "u2"], allowedUids: ["u1", "u2"],
  })));
});

for (const [name, overrides] of [
  ["organizerIds", { organizerIds: ["u2"] }],
  ["allowedUids", { allowedUids: ["u2"] }],
]) {
  test(`an event whose creator is missing from ${name} is denied`, async () => {
    await assertFails(setDoc(doc(studentDb("u1"), "events/new"), eventBy("u1", overrides)));
  });
}

test("an event's co-organizers must also be allowed readers", async () => {
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "events/new"), eventBy("u1", {
    isPrivate: true, organizerIds: ["u1", "u5"],
  })));
  await assertSucceeds(setDoc(doc(db, "events/new"), eventBy("u1", {
    isPrivate: true, organizerIds: ["u1", "u5"], allowedUids: ["u1", "u5"],
  })));
});

test("an unverified association account can't create events", async () => {
  const db = associationDb("club");
  await assertFails(setDoc(doc(db, "events/new"), eventBy("club")));
  await assertFails(setDoc(
    doc(db, "events/new"), eventBy("club", { isAssociationEvent: true }),
  ));
});

test("an unverified association account with an @epfl.ch email can't create events", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "users/epflClub"), {
      uid: "epflClub", accountType: "association", isAssociationVerified: false,
    });
  });
  const db = firestoreAs("club@epfl.ch", true, "epflClub");
  await assertFails(setDoc(doc(db, "events/new"), eventBy("epflClub")));
});

test("an unverified @epfl.ch association can't delete its profile to create events as a student", async () => {
  // Without its users/{uid}, the account would count as a student (reported in the review of
  // #102; the profile delete is denied since #161).
  await seedEpflAssociation("epflClub", false);
  const db = epflAssociationDb("epflClub");

  await assertFails(deleteDoc(doc(db, "users/epflClub")));
  await assertFails(setDoc(doc(db, "events/new"), eventBy("epflClub")));
});

test("a student can't publish an association event", async () => {
  await assertFails(setDoc(
    doc(studentDb("u1"), "events/new"), eventBy("u1", { isAssociationEvent: true }),
  ));
});

for (const [name, getDb] of [
  ["signed-out user", () => env.unauthenticatedContext().firestore()],
  ["unverified EPFL account", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["verified outside account", () => firestoreAs("student@example.org", true, "u1")],
]) {
  test(`a ${name} can't create events`, async () => {
    await assertFails(setDoc(doc(getDb(), "events/new"), eventBy("u1")));
  });
}

for (const [name, overrides] of [
  ["an empty title", { title: "" }],
  ["a blank title", { title: "   " }],
  ["a title over 80 characters", { title: "a".repeat(81) }],
  ["a missing title", { title: undefined }],
  ["a non-string title", { title: 42 }],
  ["a description over 5000 characters", { description: "a".repeat(5001) }],
  ["a missing description", { description: undefined }],
  ["an unknown category", { category: "MUSIC" }],
  ["a lowercase category", { category: "study" }],
  ["a missing category", { category: undefined }],
  ["a string visibility", { isPrivate: "false" }],
  ["a string association badge", { isAssociationEvent: "false" }],
  ["a string organizer list", { organizerIds: "u1" }],
  ["a string allowlist", { allowedUids: "u1" }],
  ["a location name over 100 characters", { locationName: "a".repeat(101) }],
  ["a non-string location name", { locationName: 42 }],
  ["a null location name", { locationName: null }],
]) {
  test(`an event with ${name} is denied`, async () => {
    const event = eventBy("u1", overrides);
    for (const key of Object.keys(event)) if (event[key] === undefined) delete event[key];
    await assertFails(setDoc(doc(studentDb("u1"), "events/new"), event));
  });
}

test("the title, description and location name limits are inclusive", async () => {
  await assertSucceeds(setDoc(doc(studentDb("u1"), "events/new"), eventBy("u1", {
    title: "a".repeat(80), description: "a".repeat(5000), locationName: "a".repeat(100),
  })));
});

// The app writes a blank name when none was picked; older events have no locationName at all.
test("an event with a blank location name is allowed", async () => {
  await assertSucceeds(setDoc(
    doc(studentDb("u1"), "events/new"), eventBy("u1", { locationName: "" }),
  ));
});

for (const category of ["STUDY", "SPORTS", "CULTURE", "PARTY", "OTHER"]) {
  test(`an event in category ${category} is allowed`, async () => {
    await assertSucceeds(setDoc(
      doc(studentDb("u1"), "events/new"), eventBy("u1", { category }),
    ));
  });
}

for (const [role, uid] of [["creator", "u2"], ["co-organizer", "u5"]]) {
  test(`the event's ${role} can update and delete it`, async () => {
    await seedManagedEvent();
    const db = studentDb(uid);
    await assertSucceeds(updateDoc(doc(db, "events/managed"), {
      title: "Moved to Rolex", isPrivate: true,
    }));
    await assertSucceeds(deleteDoc(doc(db, "events/managed")));
  });
}

test("an organizer can add another organizer", async () => {
  await seedManagedEvent();
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), {
    organizerIds: ["u2", "u5", "u3"],
  }));
});

test("an organizer added with the allowlist can read and manage a private event", async () => {
  await seedManagedEvent();
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), {
    isPrivate: true, organizerIds: ["u2", "u5", "u4"], allowedUids: ["u2", "u5", "u3", "u4"],
  }));
  const db = studentDb("u4");
  await assertSucceeds(getDoc(doc(db, "events/managed")));
  await assertSucceeds(updateDoc(doc(db, "events/managed"), { title: "Moved to Rolex" }));
});

for (const [name, getDb] of [
  ["an allowed reader who isn't an organizer", () => studentDb("u3")],
  ["an unrelated student", () => studentDb("u1")],
  ["a verified association", () => associationDb("verifiedClub")],
]) {
  test(`${name} can't update or delete someone else's event`, async () => {
    await seedManagedEvent();
    const db = getDb();
    await assertFails(updateDoc(doc(db, "events/managed"), { title: "Hijacked" }));
    await assertFails(deleteDoc(doc(db, "events/managed")));
  });
}

test("a non-organizer can't make themselves an organizer", async () => {
  await seedManagedEvent();
  await assertFails(updateDoc(doc(studentDb("u3"), "events/managed"), {
    organizerIds: ["u2", "u5", "u3"],
  }));
});

test("an organizer without a verified email can't update or delete", async () => {
  await seedManagedEvent();
  const db = firestoreAs("student@epfl.ch", false, "u5");
  await assertFails(updateDoc(doc(db, "events/managed"), { title: "Changed" }));
  await assertFails(deleteDoc(doc(db, "events/managed")));
});

for (const [name, change] of [
  ["change the creator", { createdBy: "u5" }],
  ["set the association badge", { isAssociationEvent: true }],
  ["remove the creator from the organizers", { organizerIds: ["u5"] }],
  ["remove the creator from the allowlist", { allowedUids: ["u5", "u3"] }],
  ["add an organizer who isn't in the allowlist", { organizerIds: ["u2", "u5", "u4"] }],
  ["remove a co-organizer from the allowlist", { allowedUids: ["u2", "u3"] }],
  ["empty the title", { title: "" }],
  ["set an unknown category", { category: "MUSIC" }],
  ["set a location name over 100 characters", { locationName: "a".repeat(101) }],
]) {
  test(`an organizer can't ${name}`, async () => {
    await seedManagedEvent();
    await assertFails(updateDoc(doc(studentDb("u5"), "events/managed"), change));
  });
}

test("a verified association manages its own event and keeps its badge", async () => {
  await seedEvent("clubManaged", eventBy("verifiedClub", { isAssociationEvent: true }));
  const db = associationDb("verifiedClub");
  await assertSucceeds(updateDoc(doc(db, "events/clubManaged"), { title: "Balélec warm-up" }));
  await assertFails(updateDoc(doc(db, "events/clubManaged"), { isAssociationEvent: false }));
  await assertSucceeds(deleteDoc(doc(db, "events/clubManaged")));
});

test("an association whose verification was revoked can't manage its events", async () => {
  await seedEvent("clubManaged", eventBy("verifiedClub", { isAssociationEvent: true }));
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "users/verifiedClub"), {
      isAssociationVerified: false,
    });
  });
  const db = associationDb("verifiedClub");
  await assertFails(updateDoc(doc(db, "events/clubManaged"), { title: "Changed" }));
  await assertFails(deleteDoc(doc(db, "events/clubManaged")));
});

// ---- associations/{uid} (#126) ----
// users/club is a pending association, users/verifiedClub a verified one (seeded above).

/** A valid new association, with fields overridden or removed (set to undefined) by [changes]. */
function newAssociation(changes = {}) {
  const association = {
    name: "Test Club",
    type: "CLUB",
    about: "A fictional club for tests.",
    website: "https://club.example.org",
    createdAt: serverTimestamp(),
    ...changes,
  };
  for (const key of Object.keys(association)) {
    if (association[key] === undefined) delete association[key];
  }
  return association;
}

/** Seeds users/[uid] with the rules off, as an admin or a past sign-up would. */
async function seedUser(uid, fields) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `users/${uid}`), { uid, ...fields });
  });
}

/** Seeds associations/[uid] with the rules off. */
async function seedAssociation(uid, changes = {}) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), `associations/${uid}`),
      newAssociation({ createdAt: Timestamp.fromMillis(1_790_000_000_000), ...changes }),
    );
  });
}

/** A pending association with an @epfl.ch email (#161), with its users/{uid} seeded. */
async function seededEpflAssociationDb() {
  await seedUser("epflClub", { accountType: "association", isAssociationVerified: false });
  return epflAssociationDb("epflClub");
}

// -- create --

test("a pending association can create its own associations/{uid}", async () => {
  await assertSucceeds(setDoc(doc(associationDb("club"), "associations/club"), newAssociation()));
});

test("a verified association can create its own associations/{uid}", async () => {
  const db = associationDb("verifiedClub");
  await assertSucceeds(setDoc(doc(db, "associations/verifiedClub"), newAssociation()));
});

test("an @epfl.ch association can create its own associations/{uid}", async () => {
  const db = await seededEpflAssociationDb();
  await assertSucceeds(setDoc(doc(db, "associations/epflClub"), newAssociation()));
});

test("an association can create its associations/{uid} without a website", async () => {
  const db = associationDb("club");
  await assertSucceeds(
    setDoc(doc(db, "associations/club"), newAssociation({ website: undefined })),
  );
});

for (const type of ["ASSOCIATION", "COMMISSION", "CLUB", "EPFL_UNIT"]) {
  test(`an association can create its associations/{uid} as ${type}`, async () => {
    await assertSucceeds(
      setDoc(doc(associationDb("club"), "associations/club"), newAssociation({ type })),
    );
  });
}

test("a student can't create an associations/{uid}", async () => {
  await assertFails(setDoc(doc(studentDb("u1"), "associations/u1"), newAssociation()));
});

test("an association can't create another account's associations/{uid}", async () => {
  const db = associationDb("club");
  await assertFails(setDoc(doc(db, "associations/verifiedClub"), newAssociation()));
  await assertFails(setDoc(doc(db, "associations/newClub"), newAssociation()));
});

test("an account without a profile can't create an associations/{uid}", async () => {
  await assertFails(setDoc(doc(associationDb("nobody"), "associations/nobody"), newAssociation()));
});

test("a non-EPFL account whose profile isn't an association's can't create one", async () => {
  await seedUser("plain", { displayName: "Test Account" });
  await assertFails(setDoc(doc(associationDb("plain"), "associations/plain"), newAssociation()));
});

test("an association without a verified email can't create its associations/{uid}", async () => {
  const db = firestoreAs("contact@club.org", false, "club");
  await assertFails(setDoc(doc(db, "associations/club"), newAssociation()));
});

test("a signed-out user can't create an associations/{uid}", async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertFails(setDoc(doc(db, "associations/club"), newAssociation()));
});

for (const [name, changes] of [
  ["without a name", { name: undefined }],
  ["without a type", { type: undefined }],
  ["without an about", { about: undefined }],
  ["without createdAt", { createdAt: undefined }],
  ["with a client createdAt", { createdAt: Timestamp.fromMillis(1_790_000_000_000) }],
  ["with an empty name", { name: "" }],
  ["with a blank name", { name: "   " }],
  ["with a name over 60 characters", { name: "a".repeat(61) }],
  ["with a name that isn't a string", { name: 42 }],
  ["with an empty about", { about: "" }],
  ["with a blank about", { about: " \n " }],
  ["with an about over 1000 characters", { about: "a".repeat(1001) }],
  ["with an unknown type", { type: "UNION" }],
  ["with a lowercase type", { type: "club" }],
  ["with a type that isn't a string", { type: 1 }],
  ["with an http website", { website: "http://club.example.org" }],
  ["with a website that is only https://", { website: "https://" }],
  ["with a website containing a space", { website: "https://club example.org" }],
  ["with a website over 200 characters", { website: `https://${"a".repeat(193)}` }],
  ["with a null website", { website: null }],
  ["with a website that isn't a string", { website: 7 }],
  ["with isAssociationVerified", { isAssociationVerified: true }],
  ["with a uid field", { uid: "club" }],
  ["with an email field", { email: "contact@club.org" }],
]) {
  test(`creating an associations/{uid} ${name} is denied`, async () => {
    await assertFails(
      setDoc(doc(associationDb("club"), "associations/club"), newAssociation(changes)),
    );
  });
}

test("the length limits allow exactly 60, 1000 and 200 characters", async () => {
  const db = associationDb("club");
  await assertSucceeds(setDoc(doc(db, "associations/club"), newAssociation({
    name: "a".repeat(60),
    about: "a".repeat(1000),
    website: `https://${"a".repeat(192)}`,
  })));
});

test("the name limit counts UTF-16 units, like Kotlin's String.length", async () => {
  // So #125's form and the rules agree: an accented letter is one unit, an emoji two.
  for (const [uid, name, allowed] of [
    ["accentClub", "\u00e9".repeat(60), true],
    ["emojiClub", "\u{1F389}".repeat(30), true],
    ["longEmojiClub", "\u{1F389}".repeat(31), false],
  ]) {
    await seedUser(uid, { accountType: "association", isAssociationVerified: false });
    const write = setDoc(doc(associationDb(uid), `associations/${uid}`), newAssociation({ name }));
    await (allowed ? assertSucceeds(write) : assertFails(write)).catch((error) => {
      throw new Error(`${name.length} units, allowed=${allowed}: ${error.message}`);
    });
  }
});

test("the lower limits allow a 1-character name and about and https://a", async () => {
  // Not blank is the only lower limit: a single character is a valid name or about.
  const db = associationDb("club");
  await assertSucceeds(setDoc(doc(db, "associations/club"), newAssociation({
    name: "A",
    about: "B",
    website: "https://a",
  })));
});

test("a website may hold any characters but whitespace after https://", async () => {
  // An "s" and a backslash too: the rule's [^\\s] must mean "not whitespace".
  const website = "https://assos.epfl.ch/clubs/sports?id=42&lang=fr#a\\b";
  await assertSucceeds(
    setDoc(doc(associationDb("club"), "associations/club"), newAssociation({ website })),
  );
});

test("recreating an existing associations/{uid} is denied", async () => {
  await seedAssociation("club");
  await assertFails(setDoc(doc(associationDb("club"), "associations/club"), newAssociation()));
});

// -- update --

test("a pending association can edit every field of its own associations/{uid}", async () => {
  await seedAssociation("club");
  await assertSucceeds(updateDoc(doc(associationDb("club"), "associations/club"), {
    name: "Renamed Club",
    type: "COMMISSION",
    about: "New about.",
    website: "https://new.example.org",
  }));
});

test("an association can remove its website", async () => {
  await seedAssociation("club");
  await assertSucceeds(
    updateDoc(doc(associationDb("club"), "associations/club"), { website: deleteField() }),
  );
});

test("an association can add a website later", async () => {
  await seedAssociation("club", { website: undefined });
  await assertSucceeds(updateDoc(doc(associationDb("club"), "associations/club"), {
    website: "https://club.example.org",
  }));
});

test("a verified association can edit its about and website, resending its name and type", async () => {
  await seedAssociation("verifiedClub");
  // The repository always sends every editable field; unchanged ones aren't changes.
  await assertSucceeds(updateDoc(doc(associationDb("verifiedClub"), "associations/verifiedClub"), {
    name: "Test Club",
    type: "CLUB",
    about: "New about.",
    website: "https://new.example.org",
  }));
});

for (const [name, change] of [
  ["rename itself", { name: "Another Club" }],
  ["change its type", { type: "COMMISSION" }],
]) {
  test(`a verified association can't ${name}`, async () => {
    await seedAssociation("verifiedClub");
    await assertFails(
      updateDoc(doc(associationDb("verifiedClub"), "associations/verifiedClub"), change),
    );
  });
}

for (const [name, change] of [
  ["change createdAt", { createdAt: Timestamp.fromMillis(1) }],
  ["remove createdAt", { createdAt: deleteField() }],
  ["remove the name", { name: deleteField() }],
  ["remove the about", { about: deleteField() }],
  ["blank the name", { name: " " }],
  ["empty the about", { about: "" }],
  ["set an unknown type", { type: "UNION" }],
  ["set an http website", { website: "http://club.example.org" }],
  ["set a name over 60 characters", { name: "a".repeat(61) }],
  ["add isAssociationVerified", { isAssociationVerified: true }],
  ["add another field", { logo: "https://club.example.org/logo.png" }],
]) {
  test(`an association can't ${name} on its associations/{uid}`, async () => {
    await seedAssociation("club");
    await assertFails(updateDoc(doc(associationDb("club"), "associations/club"), change));
  });
}

for (const [name, getDb] of [
  ["a student", () => studentDb("u1")],
  ["another association", () => associationDb("verifiedClub")],
  ["the owner without a verified email", () => firestoreAs("contact@club.org", false, "club")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't update or delete an association's associations/{uid}`, async () => {
    await seedAssociation("club");
    await assertFails(updateDoc(doc(getDb(), "associations/club"), { about: "Hijacked" }));
    await assertFails(deleteDoc(doc(getDb(), "associations/club")));
  });
}

// -- delete --

for (const owner of ["club", "verifiedClub"]) {
  test(`an association (${owner}) can't delete its own associations/{uid}`, async () => {
    await seedAssociation(owner);
    await assertFails(deleteDoc(doc(associationDb(owner), `associations/${owner}`)));
  });
}

// -- read --

for (const owner of ["club", "verifiedClub"]) {
  test(`an association (${owner}) can read its own associations/{uid}`, async () => {
    await seedAssociation(owner);
    await assertSucceeds(getDoc(doc(associationDb(owner), `associations/${owner}`)));
  });
}

test("an association can read its own associations/{uid} before it exists", async () => {
  // createAssociation reads the document in its transaction before creating it.
  await assertSucceeds(getDoc(doc(associationDb("club"), "associations/club")));
});

test("an association creates its associations/{uid} in a transaction", async () => {
  const db = associationDb("club");
  const ref = doc(db, "associations/club");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    if (!(await transaction.get(ref)).exists()) transaction.set(ref, newAssociation());
  }));
});

test("a student can read a verified association's associations/{uid}", async () => {
  await seedAssociation("verifiedClub");
  await assertSucceeds(getDoc(doc(studentDb("u1"), "associations/verifiedClub")));
});

test("a student can't read a pending association's associations/{uid}", async () => {
  await seedAssociation("club");
  await assertFails(getDoc(doc(studentDb("u1"), "associations/club")));
});

test("a student can't read an associations/{uid} whose owner has no profile", async () => {
  await seedAssociation("ghost");
  await assertFails(getDoc(doc(studentDb("u1"), "associations/ghost")));
});

for (const [name, fields] of [
  ['isAssociationVerified "true" (a string)', { accountType: "association", isAssociationVerified: "true" }],
  ["a verified flag on a student profile", { isAssociationVerified: true }],
]) {
  test(`a student can't read an associations/{uid} whose owner has ${name}`, async () => {
    await seedUser("oddClub", fields);
    await seedAssociation("oddClub");
    await assertFails(getDoc(doc(studentDb("u1"), "associations/oddClub")));
  });
}

test("a student can't read an association whose verification was revoked", async () => {
  await seedAssociation("verifiedClub");
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "users/verifiedClub"), {
      isAssociationVerified: false,
    });
  });
  await assertFails(getDoc(doc(studentDb("u1"), "associations/verifiedClub")));
});

for (const [name, getDb] of [
  ["another verified association", async () => {
    await seedUser("otherClub", { accountType: "association", isAssociationVerified: true });
    return associationDb("otherClub");
  }],
  ["a pending association", async () => associationDb("club")],
  ["an @epfl.ch association", seededEpflAssociationDb],
  ["a non-EPFL account without a profile", async () => associationDb("nobody")],
  ["a student without a verified email", async () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a signed-out user", async () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't read a verified association's associations/{uid}`, async () => {
    await seedAssociation("verifiedClub");
    await assertFails(getDoc(doc(await getDb(), "associations/verifiedClub")));
  });
}

// The refinement of #126: the private account data (email, verification) stays in the
// owner-only users/{uid}, even once associations/{uid} is readable.
for (const owner of ["club", "verifiedClub"]) {
  test(`neither a student nor another association can read ${owner}'s users/{uid}`, async () => {
    await seedAssociation(owner);
    await seedUser("otherClub", { accountType: "association", isAssociationVerified: true });
    for (const [name, db] of [
      ["a student", studentDb("u1")],
      ["another verified association", associationDb("otherClub")],
    ]) {
      await assertFails(getDoc(doc(db, `users/${owner}`))).catch((error) => {
        throw new Error(`${name} read users/${owner}: ${error.message}`);
      });
    }
  });
}

test("no one can list the associations collection", async () => {
  await seedAssociation("verifiedClub");
  await seedAssociation("club");
  await assertFails(getDocs(collection(studentDb("u1"), "associations")));
  const owner = associationDb("club");
  await assertFails(getDocs(collection(owner, "associations")));
  await assertFails(
    getDocs(query(collection(owner, "associations"), where(documentId(), "==", "club"))),
  );
});

test("a subcollection of an associations/{uid} is denied, even to its owner", async () => {
  await seedAssociation("club");
  const db = associationDb("club");
  await assertFails(getDoc(doc(db, "associations/club/members/u1")));
  await assertFails(setDoc(doc(db, "associations/club/members/u1"), { role: "admin" }));
});

// ---- events/{eventId}/attendees/{uid} (#121) ----

/** A registration as the app writes it: the student's UID and the server time. */
function registration(uid) {
  return { uid, registeredAt: serverTimestamp() };
}

/** Stores [uid]'s registration for events/[eventId], bypassing the rules. */
async function seedAttendee(eventId, uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `events/${eventId}/attendees/${uid}`), {
      uid, registeredAt: Timestamp.fromMillis(1_790_000_000_000),
    });
  });
}

/** u2 created "managed", u5 co-organizes it, u3 may only read it; u1 and u3 are registered. */
async function seedManagedEventWithAttendees() {
  await seedManagedEvent();
  await seedAttendee("managed", "u1");
  await seedAttendee("managed", "u3");
}

/** Fails unless [ids], sorted, are [expected]. */
function expectIds(ids, expected) {
  const sorted = [...ids].sort();
  if (JSON.stringify(sorted) !== JSON.stringify(expected)) {
    throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(sorted)}`);
  }
}

test("a student registers for a public event in a transaction, as the app does", async () => {
  const db = studentDb("u1");
  const attendee = doc(db, "events/e1/attendees/u1");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    await transaction.get(doc(db, "events/e1"));
    await transaction.get(attendee);
    transaction.set(attendee, registration("u1"));
  }));
  await assertSucceeds(getDoc(attendee));
});

for (const uid of ["u2", "u3"]) {
  test(`a student allowed in a private event (${uid}) can register for it`, async () => {
    await assertSucceeds(
      setDoc(doc(studentDb(uid), `events/private/attendees/${uid}`), registration(uid)),
    );
  });
}

test("a student can't register for a private event they aren't allowed in", async () => {
  await assertFails(setDoc(doc(studentDb("u1"), "events/private/attendees/u1"), registration("u1")));
});

test("a student can't register for an event that doesn't exist", async () => {
  await assertFails(setDoc(doc(studentDb("u1"), "events/missing/attendees/u1"), registration("u1")));
});

// deleteAccount writes the marker, then deletes the student's registrations, then their Auth user
// (#275). Their ID token stays valid meanwhile, so the rules must refuse a new registration.
test("a deleted account can't register again with its still-valid token", async () => {
  await seedDeletedAccount("u1");
  await assertFails(setDoc(doc(studentDb("u1"), "events/e1/attendees/u1"), registration("u1")));
  await assertSucceeds(setDoc(doc(studentDb("u3"), "events/e1/attendees/u3"), registration("u3")));
});

test("a student can't register another student", async () => {
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "events/e1/attendees/u3"), registration("u3")));
  await assertFails(setDoc(doc(db, "events/e1/attendees/u3"), registration("u1")));
  await assertFails(setDoc(doc(db, "events/e1/attendees/u1"), registration("u3")));
});

for (const [name, data] of [
  ["no uid", { registeredAt: serverTimestamp() }],
  ["no registeredAt", { uid: "u1" }],
  ["a registeredAt the client chose", { uid: "u1", registeredAt: Timestamp.fromMillis(0) }],
  ["another field", { ...registration("u1"), displayName: "Student One" }],
]) {
  test(`a registration with ${name} is denied`, async () => {
    await assertFails(setDoc(doc(studentDb("u1"), "events/e1/attendees/u1"), data));
  });
}

test("nobody can change a registration, not even its student", async () => {
  await seedAttendee("e1", "u1");
  const db = studentDb("u1");
  await assertFails(updateDoc(doc(db, "events/e1/attendees/u1"), {
    registeredAt: serverTimestamp(),
  }));
  await assertFails(setDoc(doc(db, "events/e1/attendees/u1"), registration("u1")));
});

for (const [name, uid, db] of [
  ["a verified association", "verifiedClub", () => associationDb("verifiedClub")],
  ["an unverified association", "club", () => associationDb("club")],
  ["an unverified @epfl.ch user", "u1", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a signed-out user", "u1", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't register or read registrations`, async () => {
    await seedAttendee("e1", "u1");
    await assertFails(setDoc(doc(db(), `events/e1/attendees/${uid}`), registration(uid)));
    await assertFails(setDoc(doc(db(), `events/clubPublic/attendees/${uid}`), registration(uid)));
    await assertFails(getDoc(doc(db(), "events/e1/attendees/u1")));
    await assertFails(getDocs(query(collectionGroup(db(), "attendees"), where("uid", "==", uid))));
  });
}

test("an @epfl.ch association can't register as a student", async () => {
  await seedEpflAssociation("epflClub", true);
  await assertFails(setDoc(
    doc(epflAssociationDb("epflClub"), "events/e1/attendees/epflClub"), registration("epflClub"),
  ));
});

test("a student can cancel their own registration, and cancel again", async () => {
  await seedAttendee("e1", "u1");
  const db = studentDb("u1");
  await assertSucceeds(deleteDoc(doc(db, "events/e1/attendees/u1")));
  await assertSucceeds(deleteDoc(doc(db, "events/e1/attendees/u1")));
});

/** [minutes] from now (negative: in the past). The emulator compares it with the real request.time. */
function minutesFromNow(minutes) {
  return Timestamp.fromMillis(Date.now() + minutes * 60_000);
}

for (const [name, times] of [
  ["whose endTime has passed", { startTime: minutesFromNow(-120), endTime: minutesFromNow(-1) }],
  ["without an endTime, once it has started", { startTime: minutesFromNow(-1), endTime: null }],
  ["with no time at all", { startTime: null, endTime: null }],
  ["whose startTime isn't a timestamp", { startTime: "tomorrow", endTime: null }],
]) {
  test(`a student can't register for an event ${name}`, async () => {
    await seedEvent("ended", eventBy("u2", times));
    await assertFails(
      setDoc(doc(studentDb("u1"), "events/ended/attendees/u1"), registration("u1")),
    );
  });
}

for (const [name, times] of [
  ["that has started but not ended", { startTime: minutesFromNow(-60), endTime: minutesFromNow(60) }],
  ["without an endTime, before it starts", { startTime: minutesFromNow(60), endTime: null }],
]) {
  test(`a student can register for an event ${name}`, async () => {
    await seedEvent("running", eventBy("u2", times));
    await assertSucceeds(
      setDoc(doc(studentDb("u1"), "events/running/attendees/u1"), registration("u1")),
    );
  });
}

test("a student can still cancel once the event has ended", async () => {
  await seedEvent("ended", eventBy("u2", { startTime: minutesFromNow(-120), endTime: minutesFromNow(-1) }));
  await seedAttendee("ended", "u1");
  await assertSucceeds(deleteDoc(doc(studentDb("u1"), "events/ended/attendees/u1")));
});

test("a student can cancel in a transaction, as the app does", async () => {
  await seedAttendee("e1", "u1");
  const db = studentDb("u1");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    transaction.delete(doc(db, "events/e1/attendees/u1"));
  }));
});

test("a student can still cancel once the event is deleted or no longer readable", async () => {
  await seedAttendee("gone", "u1");
  await seedEvent("closed", eventBy("u2", { isPrivate: true }));
  await seedAttendee("closed", "u1");
  const db = studentDb("u1");
  await assertSucceeds(deleteDoc(doc(db, "events/gone/attendees/u1")));
  await assertSucceeds(deleteDoc(doc(db, "events/closed/attendees/u1")));
});

test("a student can't cancel another student's registration, even as an organizer", async () => {
  await seedManagedEventWithAttendees();
  await assertFails(deleteDoc(doc(studentDb("u3"), "events/managed/attendees/u1")));
  await assertFails(deleteDoc(doc(studentDb("u2"), "events/managed/attendees/u1")));
});

test("a student reads their own registration, before and after it exists", async () => {
  const db = studentDb("u1");
  await assertSucceeds(getDoc(doc(db, "events/e1/attendees/u1")));
  await seedAttendee("e1", "u1");
  await assertSucceeds(getDoc(doc(db, "events/e1/attendees/u1")));
});

test("a student can't read another student's registration or the attendee list", async () => {
  await seedManagedEventWithAttendees();
  const db = studentDb("u3");
  const attendees = collection(db, "events/managed/attendees");
  await assertFails(getDoc(doc(db, "events/managed/attendees/u1")));
  await assertFails(getDocs(attendees));
  await assertFails(getDocs(query(attendees, where("uid", "==", "u1"))));
  // Filtered to their own UID, the list holds only their own registration.
  const mine = await assertSucceeds(getDocs(query(attendees, where("uid", "==", "u3"))));
  expectIds(mine.docs.map((attendee) => attendee.id), ["u3"]);
});

for (const [role, uid] of [["creator", "u2"], ["co-organizer", "u5"]]) {
  test(`the event's ${role} reads its attendee list`, async () => {
    await seedManagedEventWithAttendees();
    const db = studentDb(uid);
    const attendees = await assertSucceeds(getDocs(collection(db, "events/managed/attendees")));
    expectIds(attendees.docs.map((attendee) => attendee.id), ["u1", "u3"]);
    await assertSucceeds(getDoc(doc(db, "events/managed/attendees/u1")));
  });
}

test("an organizer can't read another event's attendee list", async () => {
  await seedManagedEventWithAttendees();
  await seedAttendee("e1", "u1");
  await assertFails(getDocs(collection(studentDb("u5"), "events/e1/attendees")));
});

test("a verified association reads the attendee list of its own event only", async () => {
  await seedEvent("clubEvent", eventBy("verifiedClub", { isAssociationEvent: true }));
  await seedAttendee("clubEvent", "u1");
  await seedManagedEventWithAttendees();
  const db = associationDb("verifiedClub");
  await assertSucceeds(getDocs(collection(db, "events/clubEvent/attendees")));
  await assertFails(getDocs(collection(db, "events/managed/attendees")));
});

test("an unverified association can't read its own event's attendee list", async () => {
  await seedEvent("pendingEvent", eventBy("club", { isAssociationEvent: true }));
  await seedAttendee("pendingEvent", "u1");
  await assertFails(getDocs(collection(associationDb("club"), "events/pendingEvent/attendees")));
});

test("a student lists their own registrations across events", async () => {
  await seedManagedEventWithAttendees();
  await seedAttendee("e1", "u1");
  await seedAttendee("e1", "u2");
  const mine = await assertSucceeds(
    getDocs(query(collectionGroup(studentDb("u1"), "attendees"), where("uid", "==", "u1"))),
  );
  expectIds(mine.docs.map((attendee) => attendee.ref.parent.parent.id), ["e1", "managed"]);
});

test("a student can't list other students' registrations across events", async () => {
  await seedAttendee("e1", "u2");
  const db = studentDb("u1");
  await assertFails(getDocs(collectionGroup(db, "attendees")));
  await assertFails(getDocs(query(collectionGroup(db, "attendees"), where("uid", "==", "u2"))));
});

// ---- publicProfiles/{uid} (#229) ----
// What other students see of a student: exactly displayName, section, year and interests, copied
// from the owner's users/{uid} in the same write. Never the email. Associations see none.

/** The private profile of a fictional student [uid], with fields overridden by [changes]. */
function privateProfile(uid, changes = {}) {
  const profile = {
    uid,
    email: `${uid}@epfl.ch`,
    displayName: "Test Student",
    section: "IN",
    year: "BA3",
    accountType: "student",
    ...changes,
  };
  for (const key of Object.keys(profile)) {
    if (profile[key] === undefined) delete profile[key];
  }
  return profile;
}

/**
 * The public profile mirroring [privateProfile]: its displayName, section and year, and no
 * interests (#230). Fields are overridden, or removed when set to undefined.
 */
function publicProfile(changes = {}) {
  const profile = { displayName: "Test Student", section: "IN", year: "BA3", interests: [], ...changes };
  for (const key of Object.keys(profile)) {
    if (profile[key] === undefined) delete profile[key];
  }
  return profile;
}

/** Seeds the private and public profiles of a fictional student [uid] with the rules off. */
async function seedStudentWithPublicProfile(uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, `users/${uid}`), privateProfile(uid));
    await setDoc(doc(db, `publicProfiles/${uid}`), publicProfile());
  });
}

/** Creates users/[uid] and publicProfiles/[uid] in one batch, as the profile step does. */
function createBoth(db, uid, privateChanges = {}, publicChanges = {}) {
  const batch = writeBatch(db);
  batch.set(doc(db, `users/${uid}`), privateProfile(uid, privateChanges));
  batch.set(doc(db, `publicProfiles/${uid}`), publicProfile(publicChanges));
  return batch.commit();
}

// -- read --

test("a student can read another student's public profile", async () => {
  await seedStudentWithPublicProfile("u2");
  const snapshot = await assertSucceeds(getDoc(doc(studentDb("u1"), "publicProfiles/u2")));
  if (snapshot.data().section !== "IN") throw new Error("Expected u2's public profile");
});

// No student directory: a student reads public profiles one by one, by UID, never as a list.
for (const [name, listing] of [
  ["the whole collection", (profiles) => profiles],
  ["a page of it", (profiles) => query(profiles, limit(2))],
  ["several by ID", (profiles) => query(profiles, where(documentId(), "in", ["u2", "u3"]))],
]) {
  test(`a student can't list public profiles: ${name}`, async () => {
    await seedStudentWithPublicProfile("u2");
    await seedStudentWithPublicProfile("u3");
    await assertFails(getDocs(listing(collection(studentDb("u1"), "publicProfiles"))));
  });
}

test("a student can read a public profile that doesn't exist yet", async () => {
  await assertSucceeds(getDoc(doc(studentDb("u1"), "publicProfiles/missing")));
});

for (const [name, db] of [
  ["a pending association", () => associationDb("club")],
  ["a verified association", () => associationDb("verifiedClub")],
  ["an @epfl.ch association", () => epflAssociationDb("verifiedClub")],
  ["an unverified student", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a non-EPFL account without a profile", () => associationDb("nobody")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't read public profiles`, async () => {
    await seedStudentWithPublicProfile("u2");
    await assertFails(getDoc(doc(db(), "publicProfiles/u2")));
  });
}

// -- create and update --

/** The codes listed by [functionName] in firestore.rules, such as isKnownSection. */
function rulesCodes(functionName) {
  const rules = readFileSync(path.join(__dirname, "firestore.rules"), "utf8");
  const body = rules.match(new RegExp(`function ${functionName}\\([^)]*\\) \\{[^}]*\\}`));
  if (!body) throw new Error(`firestore.rules has no ${functionName}: update this test`);
  return [...body[0].matchAll(/'([^']+)'/g)].map((match) => match[1]);
}

test("the rules accept exactly the section and year codes the app offers", () => {
  const { sections, years } = appProfileOptions();
  const sorted = (codes) => [...codes].sort().join(",");
  if (sorted(rulesCodes("isKnownSection")) !== sorted(sections)) {
    throw new Error("isKnownSection in firestore.rules differs from SECTIONS in ProfileOptions.kt");
  }
  if (sorted(rulesCodes("isKnownYear")) !== sorted(years)) {
    throw new Error("isKnownYear in firestore.rules differs from YEARS in ProfileOptions.kt");
  }
});

test("every section and year the app offers can be made public", async () => {
  const { sections, years } = appProfileOptions();
  const codes = [
    ...sections.map((section) => ({ section })),
    ...years.map((year) => ({ year })),
  ];
  for (const [i, changes] of codes.entries()) {
    const uid = `option${i}`;
    await assertSucceeds(createBoth(studentDb(uid), uid, changes, changes));
  }
});

for (const [name, changes] of [
  ["an unknown section", { section: "XX" }],
  ["a lowercase section", { section: "in" }],
  ["an overlong section", { section: "IN".repeat(5000) }],
  ["a section that isn't a string", { section: 7 }],
  ["an unknown year", { year: "BA7" }],
  ["an overlong year", { year: "BA3".repeat(5000) }],
  ["a year that isn't a string", { year: 3 }],
]) {
  test(`a public profile with ${name}, even copied from the private one, is denied`, async () => {
    await assertFails(createBoth(studentDb("u5"), "u5", changes, changes));
  });

  test(`updating a public profile to ${name}, even copied from the private one, is denied`, async () => {
    await seedStudentWithPublicProfile("u5");
    const db = studentDb("u5");
    const batch = writeBatch(db);
    batch.update(doc(db, "users/u5"), changes);
    batch.set(doc(db, "publicProfiles/u5"), publicProfile(changes));
    await assertFails(batch.commit());
  });
}

test("a student creates their profile and public profile together", async () => {
  await assertSucceeds(createBoth(studentDb("u5"), "u5"));
});

test("a student creates their profile and public profile in a transaction", async () => {
  const db = studentDb("u5");
  await assertSucceeds(
    runTransaction(db, async (transaction) => {
      await transaction.get(doc(db, "users/u5"));
      transaction.set(doc(db, "users/u5"), privateProfile("u5"));
      transaction.set(doc(db, "publicProfiles/u5"), publicProfile());
    }),
  );
});

test("a student with a saved profile can add the public profile on its own", async () => {
  await seedUser("u5", privateProfile("u5"));
  await assertSucceeds(setDoc(doc(studentDb("u5"), "publicProfiles/u5"), publicProfile()));
});

test("a student edits their profile and public profile together", async () => {
  await seedStudentWithPublicProfile("u5");
  const db = studentDb("u5");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u5"), { displayName: "New Name", section: "SC", year: "MA1" });
  batch.set(doc(db, "publicProfiles/u5"), publicProfile({ displayName: "New Name", section: "SC", year: "MA1" }));
  await assertSucceeds(batch.commit());
});

test("an edit's public profile must match the private profile as edited, not as it was", async () => {
  await seedStudentWithPublicProfile("u5");
  const db = studentDb("u5");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u5"), { displayName: "New Name" });
  batch.set(doc(db, "publicProfiles/u5"), publicProfile());
  await assertFails(batch.commit());
});

for (const [name, changes] of [
  ["another displayName", { displayName: "Someone Else" }],
  ["another section", { section: "SC" }],
  ["another year", { year: "MA2" }],
]) {
  test(`a public profile with ${name} than the private one is denied`, async () => {
    await assertFails(createBoth(studentDb("u5"), "u5", {}, changes));
  });

  test(`updating a public profile to ${name} than the private one is denied`, async () => {
    await seedStudentWithPublicProfile("u5");
    await assertFails(updateDoc(doc(studentDb("u5"), "publicProfiles/u5"), changes));
  });
}

for (const [name, changes] of [
  ["the email", { email: "u5@epfl.ch" }],
  ["the uid", { uid: "u5" }],
  ["the account type", { accountType: "student" }],
  ["another field", { bio: "Hello" }],
  ["no displayName", { displayName: undefined }],
  ["no section", { section: undefined }],
  ["no year", { year: undefined }],
  ["no interests", { interests: undefined }],
  ["an interest the private profile doesn't have", { interests: ["music"] }],
  ["interests that aren't a list", { interests: "MUSIC" }],
]) {
  test(`a public profile with ${name} is denied`, async () => {
    await assertFails(createBoth(studentDb("u5"), "u5", {}, changes));
  });
}

test("a student can't add a field to their public profile later", async () => {
  await seedStudentWithPublicProfile("u5");
  await assertFails(updateDoc(doc(studentDb("u5"), "publicProfiles/u5"), { email: "u5@epfl.ch" }));
});

test("a student can't write another student's public profile", async () => {
  await seedStudentWithPublicProfile("u2");
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "publicProfiles/u2"), publicProfile({ displayName: "Changed" })));
  await assertFails(updateDoc(doc(db, "publicProfiles/u2"), { displayName: "Changed" }));
  await assertFails(setDoc(doc(db, "publicProfiles/u6"), publicProfile()));
});

test("a student can't write an exact copy of another student's public profile", async () => {
  // Name, section and year are public, so anyone could rebuild a correct copy.
  await seedStudentWithPublicProfile("u2");
  await seedUser("u7", privateProfile("u7"));
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "publicProfiles/u2"), publicProfile()));
  await assertFails(setDoc(doc(db, "publicProfiles/u7"), publicProfile()));
});

test("a public profile without a private profile is denied", async () => {
  await assertFails(setDoc(doc(studentDb("u5"), "publicProfiles/u5"), publicProfile()));
});

test("a private profile without a displayName can't get a public profile", async () => {
  await seedUser("u5", privateProfile("u5", { displayName: undefined }));
  await assertFails(setDoc(doc(studentDb("u5"), "publicProfiles/u5"), publicProfile()));
});

for (const [name, db, uid] of [
  ["A pending association", () => associationDb("club"), "club"],
  ["A verified association", () => associationDb("verifiedClub"), "verifiedClub"],
  ["An @epfl.ch association", () => epflAssociationDb("verifiedClub"), "verifiedClub"],
]) {
  test(`${name} can't write a public profile`, async () => {
    await assertFails(setDoc(doc(db(), `publicProfiles/${uid}`), publicProfile()));
  });
}

test("an @epfl.ch account creating an association profile can't add a public profile", async () => {
  await assertFails(
    createBoth(studentDb("u5"), "u5", { accountType: "association", isAssociationVerified: false }),
  );
});

test("a profile without an accountType counts as a student's", async () => {
  await assertSucceeds(createBoth(studentDb("u5"), "u5", { accountType: undefined }));
});

test("a non-EPFL account whose profile says student can't write a public profile", async () => {
  await seedUser("u5", privateProfile("u5", { email: "someone@example.org" }));
  await assertFails(setDoc(doc(associationDb("u5"), "publicProfiles/u5"), publicProfile()));
});

for (const [name, db] of [
  ["an unverified student", () => firestoreAs("u5@epfl.ch", false, "u5")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't write a public profile`, async () => {
    await seedUser("u5", privateProfile("u5"));
    await assertFails(setDoc(doc(db(), "publicProfiles/u5"), publicProfile()));
  });
}

// -- delete --

test("a student can't delete their own public profile", async () => {
  await seedStudentWithPublicProfile("u5");
  await assertFails(deleteDoc(doc(studentDb("u5"), "publicProfiles/u5")));
});

test("nobody else can delete a public profile", async () => {
  await seedStudentWithPublicProfile("u2");
  await assertFails(deleteDoc(doc(studentDb("u1"), "publicProfiles/u2")));
  await assertFails(deleteDoc(doc(associationDb("verifiedClub"), "publicProfiles/u2")));
});

// -- a private edit keeps the public copy in step (#229 review) --
// Once a public profile exists, a change to a field it mirrors must update it in the same write,
// or other students would keep seeing the old values.

for (const [name, changes] of [
  ["displayName", { displayName: "New Name" }],
  ["section", { section: "SC" }],
  ["year", { year: "MA1" }],
]) {
  test(`changing only the private ${name} is denied once a public profile exists`, async () => {
    await seedStudentWithPublicProfile("u5");
    await assertFails(updateDoc(doc(studentDb("u5"), "users/u5"), changes));
  });

  test(`changing the private ${name} with a public copy that keeps the old value is denied`, async () => {
    await seedStudentWithPublicProfile("u5");
    const db = studentDb("u5");
    const batch = writeBatch(db);
    batch.update(doc(db, "users/u5"), changes);
    batch.update(doc(db, "publicProfiles/u5"), { interests: [] });
    await assertFails(batch.commit());
  });

  test(`changing the private ${name} is allowed without a public profile (saved before #229)`, async () => {
    await seedUser("u5", privateProfile("u5"));
    await assertSucceeds(updateDoc(doc(studentDb("u5"), "users/u5"), changes));
  });
}

test("removing the private displayName is denied once a public profile exists", async () => {
  await seedStudentWithPublicProfile("u5");
  await assertFails(updateDoc(doc(studentDb("u5"), "users/u5"), { displayName: deleteField() }));
});

test("a private edit that leaves the mirrored fields unchanged doesn't need the public copy", async () => {
  await seedStudentWithPublicProfile("u5");
  await assertSucceeds(
    updateDoc(doc(studentDb("u5"), "users/u5"), { displayName: "Test Student", email: "u5@epfl.ch" }),
  );
});

test("an edit adding the public copy of a profile saved before #229 is allowed", async () => {
  await seedUser("u5", privateProfile("u5"));
  const db = studentDb("u5");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u5"), { displayName: "New Name" });
  batch.set(doc(db, "publicProfiles/u5"), publicProfile({ displayName: "New Name" }));
  await assertSucceeds(batch.commit());
});

test("an association can rename itself in its users/{uid}, having no public profile", async () => {
  await seedUser("club", { email: "club@example.org", accountType: "association", displayName: "" });
  await assertSucceeds(updateDoc(doc(associationDb("club"), "users/club"), { displayName: "Club" }));
});

// -- a deleted account stays deleted (#229 review) --
// deleteAccount writes deletedAccounts/{uid} in the transaction that deletes both profiles. The
// deleted user's ID token stays valid until it expires, so without the marker another of their
// devices could recreate the profiles, and the public one would be readable after the deletion.

/** Seeds the deletion marker deleteAccount leaves for [uid], with the rules off. */
async function seedDeletedAccount(uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `deletedAccounts/${uid}`), {});
  });
}

test("a deleted account can't recreate its profile and public profile", async () => {
  await seedDeletedAccount("u5");
  await assertFails(createBoth(studentDb("u5"), "u5"));
  await assertFails(setDoc(doc(studentDb("u5"), "users/u5"), privateProfile("u5")));
});

test("a deleted account can't write a public profile, even with a private one left", async () => {
  // Not reachable through deleteAccount, which removes both in one transaction; checked anyway.
  await seedUser("u5", privateProfile("u5"));
  await seedDeletedAccount("u5");
  await assertFails(setDoc(doc(studentDb("u5"), "publicProfiles/u5"), publicProfile()));
});

test("an association whose account was deleted can't recreate its profile", async () => {
  await seedDeletedAccount("goneClub");
  await assertFails(
    setDoc(doc(associationDb("goneClub"), "users/goneClub"), {
      uid: "goneClub",
      email: "club@example.org",
      accountType: "association",
      displayName: "",
    }),
  );
});

test("another account's deletion marker doesn't stop a student creating their profile", async () => {
  await seedDeletedAccount("u2");
  await assertSucceeds(createBoth(studentDb("u5"), "u5"));
});

test("nobody can read, write or delete a deletion marker from the app", async () => {
  await seedDeletedAccount("u5");
  for (const db of [studentDb("u5"), studentDb("u1"), associationDb("verifiedClub")]) {
    await assertFails(getDoc(doc(db, "deletedAccounts/u5")));
    await assertFails(deleteDoc(doc(db, "deletedAccounts/u5")));
    await assertFails(setDoc(doc(db, "deletedAccounts/u1"), {}));
  }
  await assertFails(getDocs(collection(studentDb("u1"), "deletedAccounts")));
});

// -- interests (#230) --
// A student picks none to all of a fixed list of interests, stored as codes in users/{uid} and
// copied, in the same order, to publicProfiles/{uid}. A profile saved before #230 has none.

test("the rules accept exactly the interest codes the app offers", () => {
  const sorted = (codes) => [...codes].sort().join(",");
  if (sorted(rulesCodes("interestCodes")) !== sorted(appInterests())) {
    throw new Error("interestCodes in firestore.rules differs from INTERESTS in ProfileOptions.kt");
  }
});

test("a student can save every interest the app offers and make them public", async () => {
  const interests = appInterests();
  await assertSucceeds(createBoth(studentDb("u5"), "u5", { interests }, { interests }));
});

test("a student can save some interests and make them public", async () => {
  const interests = ["music", "tech"];
  await assertSucceeds(createBoth(studentDb("u5"), "u5", { interests }, { interests }));
});

test("a student can save no interests", async () => {
  await assertSucceeds(createBoth(studentDb("u5"), "u5", { interests: [] }, { interests: [] }));
});

for (const [name, interests] of [
  ["an unknown interest", ["chess"]],
  ["an interest in capitals", ["MUSIC"]],
  ["a known and an unknown interest", ["music", "chess"]],
  ["the same interest twice", ["music", "music"]],
  ["interests that aren't a list", "music"],
  ["an interest that isn't a string", [7]],
  ["an overlong interest", ["music".repeat(5000)]],
]) {
  test(`a private profile with ${name} is denied`, async () => {
    await assertFails(setDoc(doc(studentDb("u5"), "users/u5"), privateProfile("u5", { interests })));
  });

  test(`a private and public profile with ${name} are denied together`, async () => {
    await assertFails(createBoth(studentDb("u5"), "u5", { interests }, { interests }));
  });

  test(`updating a private profile to ${name} is denied`, async () => {
    // Saved before #229, so no public copy is involved.
    await seedUser("u5", privateProfile("u5"));
    await assertFails(updateDoc(doc(studentDb("u5"), "users/u5"), { interests }));
  });
}

for (const [name, privateInterests, publicInterests] of [
  ["fewer interests", ["music", "tech"], ["music"]],
  ["more interests", ["music"], ["music", "tech"]],
  ["no interests", ["music"], []],
  ["the interests in another order", ["music", "tech"], ["tech", "music"]],
]) {
  test(`a public profile with ${name} than the private one is denied`, async () => {
    await assertFails(
      createBoth(studentDb("u5"), "u5", { interests: privateInterests }, { interests: publicInterests }),
    );
  });
}

test("a private profile saved before #230 has no interests, so its copy has none", async () => {
  await seedUser("u5", privateProfile("u5"));
  await assertSucceeds(setDoc(doc(studentDb("u5"), "publicProfiles/u5"), publicProfile()));
  await assertFails(
    setDoc(doc(studentDb("u5"), "publicProfiles/u5"), publicProfile({ interests: ["music"] })),
  );
});

test("changing only the private interests is denied once a public profile exists", async () => {
  await seedStudentWithPublicProfile("u5");
  await assertFails(updateDoc(doc(studentDb("u5"), "users/u5"), { interests: ["music"] }));
});

test("a student edits their interests in both profiles together", async () => {
  await seedStudentWithPublicProfile("u5");
  const db = studentDb("u5");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u5"), { interests: ["music", "tech"] });
  batch.update(doc(db, "publicProfiles/u5"), { interests: ["music", "tech"] });
  await assertSucceeds(batch.commit());
});

test("changing the private interests is allowed without a public profile", async () => {
  await seedUser("u5", privateProfile("u5"));
  await assertSucceeds(updateDoc(doc(studentDb("u5"), "users/u5"), { interests: ["music"] }));
});

test("an unknown interest can't be made public, even copied from a private profile that has it", async () => {
  // Only an admin could store it, with the rules off; the public copy still refuses it.
  await seedUser("u5", privateProfile("u5", { interests: ["chess"] }));
  await assertFails(
    setDoc(doc(studentDb("u5"), "publicProfiles/u5"), publicProfile({ interests: ["chess"] })),
  );
});

// ---- events/{eventId}/groups/{groupId} and groupMembers/{uid} (#232) ----
// A group has capacity 4, memberUids (creator first), createdBy and a server createdAt. Each
// student changes only their own UID, together with their groupMembers marker, in one write.

/** A new group as the app writes it: [uid] as its creator and only member. */
function newGroup(uid) {
  return { capacity: 4, memberUids: [uid], createdBy: uid, createdAt: serverTimestamp() };
}

/** Stores events/[eventId]/groups/[groupId] with [members] and each member's marker, rules off. */
async function seedGroup(eventId, groupId, members) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, `events/${eventId}/groups/${groupId}`), {
      capacity: 4,
      memberUids: members,
      createdBy: members[0],
      createdAt: Timestamp.fromMillis(1_790_000_000_000),
    });
    for (const uid of members) {
      await setDoc(doc(db, `events/${eventId}/groupMembers/${uid}`), { groupId });
    }
  });
}

/** The stored memberUids of events/[eventId]/groups/[groupId], or null without it, rules off. */
async function storedMembers(eventId, groupId) {
  let members;
  await env.withSecurityRulesDisabled(async (context) => {
    const snapshot = await getDoc(doc(context.firestore(), `events/${eventId}/groups/${groupId}`));
    members = snapshot.exists() ? snapshot.data().memberUids : null;
  });
  return members;
}

/** Fails unless the stored members of [groupId] are exactly [expected], in order. */
async function expectMembers(eventId, groupId, expected) {
  const members = await storedMembers(eventId, groupId);
  if (JSON.stringify(members) !== JSON.stringify(expected)) {
    throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(members)}`);
  }
}

/** Starts the group [groupId] as [uid], the way FirestoreGroupRepository.createGroup does. */
function createGroupAs(db, uid, eventId, groupId, group = newGroup(uid)) {
  return runTransaction(db, async (transaction) => {
    await transaction.get(doc(db, `events/${eventId}`));
    await transaction.get(doc(db, `events/${eventId}/groupMembers/${uid}`));
    transaction.set(doc(db, `events/${eventId}/groups/${groupId}`), group);
    transaction.set(doc(db, `events/${eventId}/groupMembers/${uid}`), { groupId });
  });
}

/** Joins [groupId] as [uid], the way joinGroup does: [uid] appended, the marker created. */
function joinAs(db, uid, eventId, groupId) {
  const group = doc(db, `events/${eventId}/groups/${groupId}`);
  return runTransaction(db, async (transaction) => {
    const members = (await transaction.get(group)).data().memberUids;
    await transaction.get(doc(db, `events/${eventId}/groupMembers/${uid}`));
    transaction.update(group, { memberUids: [...members, uid] });
    transaction.set(doc(db, `events/${eventId}/groupMembers/${uid}`), { groupId });
  });
}

/** Leaves [groupId] as [uid], the way leaveGroup does: removed, or the group deleted if last. */
function leaveAs(db, uid, eventId, groupId) {
  const group = doc(db, `events/${eventId}/groups/${groupId}`);
  return runTransaction(db, async (transaction) => {
    const members = (await transaction.get(group)).data().memberUids;
    await transaction.get(doc(db, `events/${eventId}/groupMembers/${uid}`));
    const remaining = members.filter((member) => member !== uid);
    if (remaining.length === 0) transaction.delete(group);
    else transaction.update(group, { memberUids: remaining });
    transaction.delete(doc(db, `events/${eventId}/groupMembers/${uid}`));
  });
}

/**
 * Writes [members] as the memberUids of [groupId] in one batch, together with whatever
 * [markerWrites] adds, given the batch and a function from a UID to its marker.
 */
function writeMembers(db, eventId, groupId, members, markerWrites = () => {}) {
  const batch = writeBatch(db);
  batch.update(doc(db, `events/${eventId}/groups/${groupId}`), { memberUids: members });
  markerWrites(batch, (uid) => doc(db, `events/${eventId}/groupMembers/${uid}`));
  return batch.commit();
}

// -- reading groups --

test("a student reads and lists the groups of an event they can read", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  await seedGroup("private", "g1", ["u2"]);
  const db = studentDb("u1");
  await assertSucceeds(getDoc(doc(db, "events/e1/groups/g1")));
  const listed = await assertSucceeds(getDocs(collection(db, "events/e1/groups")));
  expectIds(listed.docs.map((group) => group.id), ["g1"]);
  await assertSucceeds(getDocs(collection(studentDb("u3"), "events/private/groups")));
});

test("a student can't read the groups of an event they can't read", async () => {
  await seedGroup("private", "g1", ["u2"]);
  await seedGroup("gone", "g1", ["u2"]);
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, "events/private/groups/g1")));
  await assertFails(getDocs(collection(db, "events/private/groups")));
  await assertFails(getDocs(collection(db, "events/gone/groups")));
});

test("a student lists their own groups across events", async () => {
  await seedGroup("e1", "mine", ["u2", "u1"]);
  await seedGroup("private", "theirs", ["u2"]);
  await seedGroup("gone", "old", ["u1"]);
  const mine = await assertSucceeds(getDocs(query(
    collectionGroup(studentDb("u1"), "groups"), where("memberUids", "array-contains", "u1"),
  )));
  expectIds(
    mine.docs.map((group) => `${group.ref.parent.parent.id}/${group.id}`), ["e1/mine", "gone/old"],
  );
});

/** Takes u3 out of the private event's allowedUids, as its organizers would, rules off. */
async function removeU3FromPrivateEvent() {
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "events/private"), { allowedUids: ["u2", "u4"] });
  });
}

test("a member removed from a private event still lists and gets only their group", async () => {
  await seedGroup("private", "g1", ["u2", "u3"]);
  await seedGroup("private", "g2", ["u4"]);
  await removeU3FromPrivateEvent();
  const db = studentDb("u3");
  // The query FirestoreGroupRepository.observeMyGroup runs, allowed by /{path=**}/groups.
  const mine = await assertSucceeds(getDocs(query(
    collection(db, "events/private/groups"), where("memberUids", "array-contains", "u3"),
  )));
  expectIds(mine.docs.map((group) => group.id), ["g1"]);
  await assertSucceeds(getDoc(doc(db, "events/private/groups/g1")));
  await assertFails(getDoc(doc(db, "events/private/groups/g2")));
  await assertFails(getDoc(doc(db, "events/private/groups/missing")));
  await assertFails(getDocs(collection(db, "events/private/groups")));
});

test("a student can't list other students' groups across events", async () => {
  await seedGroup("private", "g1", ["u2"]);
  const db = studentDb("u1");
  await assertFails(getDocs(collectionGroup(db, "groups")));
  await assertFails(getDocs(query(
    collectionGroup(db, "groups"), where("memberUids", "array-contains", "u2"),
  )));
});

for (const [name, uid, db] of [
  ["a verified association", "verifiedClub", () => associationDb("verifiedClub")],
  ["an unverified association", "club", () => associationDb("club")],
  ["an @epfl.ch association", "epflClub", () => epflAssociationDb("epflClub")],
  ["an unverified @epfl.ch user", "u1", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a signed-out user", "u1", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't read, start, join or leave groups`, async () => {
    await seedEpflAssociation("epflClub", true);
    await seedGroup("e1", "g1", ["u2"]);
    await seedGroup("clubPublic", "g1", [uid, "u2"]);
    await assertFails(getDoc(doc(db(), "events/e1/groups/g1")));
    await assertFails(getDocs(collection(db(), "events/clubPublic/groups")));
    await assertFails(getDocs(query(
      collectionGroup(db(), "groups"), where("memberUids", "array-contains", uid),
    )));
    await assertFails(getDoc(doc(db(), `events/clubPublic/groupMembers/${uid}`)));
    await assertFails(createGroupAs(db(), uid, "e1", "new"));
    await assertFails(writeMembers(db(), "e1", "g1", ["u2", uid], (batch, marker) => {
      batch.set(marker(uid), { groupId: "g1" });
    }));
    await assertFails(writeMembers(db(), "clubPublic", "g1", ["u2"], (batch, marker) => {
      batch.delete(marker(uid));
    }));
  });
}

// -- starting a group --

test("a student starts a group with their marker in one transaction, as the app does", async () => {
  await assertSucceeds(createGroupAs(studentDb("u1"), "u1", "e1", "new"));
  await assertSucceeds(createGroupAs(studentDb("u3"), "u3", "private", "new"));
  await expectMembers("e1", "new", ["u1"]);
});

test("a student can't start a group without their marker", async () => {
  await assertFails(setDoc(doc(studentDb("u1"), "events/e1/groups/new"), newGroup("u1")));
});

test("a student can't start a group with a marker naming another group", async () => {
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "events/e1/groups/new"), newGroup("u1"));
  batch.set(doc(db, "events/e1/groupMembers/u1"), { groupId: "other" });
  await assertFails(batch.commit());
});

for (const [name, changes] of [
  ["another student as its member", { memberUids: ["u3"] }],
  ["a second member", { memberUids: ["u1", "u3"] }],
  ["the creator second", { memberUids: ["u3", "u1"] }],
  ["no member", { memberUids: [] }],
  ["5 places", { capacity: 5 }],
  ["a text capacity", { capacity: "4" }],
  ["another creator", { createdBy: "u3" }],
  ["a createdAt the client chose", { createdAt: Timestamp.fromMillis(0) }],
  ["no createdAt", { createdAt: undefined }],
  ["another field", { title: "Our group" }],
]) {
  test(`a student can't start a group with ${name}`, async () => {
    const group = { ...newGroup("u1"), ...changes };
    for (const key of Object.keys(group)) {
      if (group[key] === undefined) delete group[key];
    }
    await assertFails(createGroupAs(studentDb("u1"), "u1", "e1", "new", group));
  });
}

test("a student can't start a group for an event they can't read", async () => {
  await assertFails(createGroupAs(studentDb("u1"), "u1", "private", "new"));
  await assertFails(createGroupAs(studentDb("u1"), "u1", "gone", "new"));
});

test("a student already in a group of the event can't start another", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await assertFails(createGroupAs(studentDb("u1"), "u1", "e1", "new"));
  await expectMembers("e1", "new", null);
});

test("a student with a marker can't start a group, even the one it names", async () => {
  // A marker left behind for a group that no longer exists: the student deletes it first.
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "events/e1/groupMembers/u1"), { groupId: "new" });
  });
  await assertFails(setDoc(doc(studentDb("u1"), "events/e1/groups/new"), newGroup("u1")));
  await expectMembers("e1", "new", null);
});

test("a group of another event doesn't stop a student starting one", async () => {
  await seedGroup("private", "g1", ["u3"]);
  await assertSucceeds(createGroupAs(studentDb("u3"), "u3", "e1", "new"));
});

// -- joining --

test("a student joins a group with a free place, with their marker, as the app does", async () => {
  await seedGroup("e1", "g1", ["u2", "u3", "u4"]);
  await assertSucceeds(joinAs(studentDb("u1"), "u1", "e1", "g1"));
  await expectMembers("e1", "g1", ["u2", "u3", "u4", "u1"]);
});

test("a student can't join without creating their marker", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u2", "u1"]));
});

test("a student can't join with a marker naming another group", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u2", "u1"], (batch, marker) => {
    batch.set(marker("u1"), { groupId: "g2" });
  }));
});

test("a student can't join a full group", async () => {
  await seedGroup("e1", "g1", ["u2", "u3", "u4", "u5"]);
  await assertFails(joinAs(studentDb("u1"), "u1", "e1", "g1"));
});

test("a stale join can't take the last place someone else just took", async () => {
  await seedGroup("e1", "g1", ["u2", "u3", "u4"]);
  await assertSucceeds(joinAs(studentDb("u1"), "u1", "e1", "g1"));
  // u5 read the group before u1 joined, and writes what it read plus itself.
  const stale = ["u2", "u3", "u4", "u5"];
  await assertFails(writeMembers(studentDb("u5"), "e1", "g1", stale, (batch, marker) => {
    batch.set(marker("u5"), { groupId: "g1" });
  }));
  await assertFails(joinAs(studentDb("u5"), "u5", "e1", "g1"));
  await expectMembers("e1", "g1", ["u2", "u3", "u4", "u1"]);
});

test("a student already in a group of the event can't join another", async () => {
  await seedGroup("e1", "g1", ["u1"]);
  await seedGroup("e1", "g2", ["u2"]);
  const db = studentDb("u1");
  await assertFails(joinAs(db, "u1", "e1", "g2"));
  await assertFails(writeMembers(db, "e1", "g2", ["u2", "u1"]));
});

test("a member can't add themselves a second time", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u2", "u1", "u1"]));
});

test("a student can't join a group of an event they can't read", async () => {
  await seedGroup("private", "g1", ["u2"]);
  await assertFails(joinAs(studentDb("u1"), "u1", "private", "g1"));
});

for (const [name, members] of [
  ["add another student", ["u2", "u3"]],
  ["add themselves and another student", ["u2", "u1", "u3"]],
  ["remove another student", []],
  ["swap another student for themselves", ["u1"]],
  ["join in first place", ["u1", "u2"]],
]) {
  test(`a student can't ${name}`, async () => {
    await seedGroup("e1", "g1", ["u2"]);
    await assertFails(writeMembers(studentDb("u1"), "e1", "g1", members, (batch, marker) => {
      batch.set(marker("u1"), { groupId: "g1" });
    }));
    await expectMembers("e1", "g1", ["u2"]);
  });
}

test("a member can't remove another member", async () => {
  await seedGroup("e1", "g1", ["u2", "u1", "u3"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u2", "u1"]));
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u1", "u3"], (batch, marker) => {
    batch.delete(marker("u2"));
  }));
});

for (const [name, change] of [
  ["its capacity", { capacity: 5 }],
  ["its creator", { createdBy: "u1" }],
  ["its creation time", { createdAt: serverTimestamp() }],
]) {
  test(`a student can't change ${name}, even while joining`, async () => {
    await seedGroup("e1", "g1", ["u2"]);
    const db = studentDb("u1");
    const batch = writeBatch(db);
    batch.update(doc(db, "events/e1/groups/g1"), { memberUids: ["u2", "u1"], ...change });
    batch.set(doc(db, "events/e1/groupMembers/u1"), { groupId: "g1" });
    await assertFails(batch.commit());
  });
}

// -- leaving --

test("a member leaves, deleting their marker, as the app does", async () => {
  await seedGroup("e1", "g1", ["u2", "u1", "u3"]);
  await assertSucceeds(leaveAs(studentDb("u1"), "u1", "e1", "g1"));
  await expectMembers("e1", "g1", ["u2", "u3"]);
});

test("a member can't leave without deleting their marker", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u2"]));
});

test("a member removed from a private event still leaves their group, as the app does", async () => {
  await seedGroup("private", "g1", ["u2", "u3"]);
  await removeU3FromPrivateEvent();
  await assertSucceeds(leaveAs(studentDb("u3"), "u3", "private", "g1"));
  await expectMembers("private", "g1", ["u2"]);
});

test("a student who can't read the event can't leave a group they aren't in", async () => {
  await seedGroup("private", "g1", ["u2"]);
  await assertFails(leaveAs(studentDb("u1"), "u1", "private", "g1"));
  await expectMembers("private", "g1", ["u2"]);
});

test("the last member leaving deletes the group and their marker, as the app does", async () => {
  await seedGroup("e1", "g1", ["u1"]);
  await assertSucceeds(leaveAs(studentDb("u1"), "u1", "e1", "g1"));
  await expectMembers("e1", "g1", null);
});

test("the last member can't empty the group instead of deleting it", async () => {
  await seedGroup("e1", "g1", ["u1"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", [], (batch, marker) => {
    batch.delete(marker("u1"));
  }));
});

test("the last member can't delete the group and keep their marker", async () => {
  await seedGroup("e1", "g1", ["u1"]);
  await assertFails(deleteDoc(doc(studentDb("u1"), "events/e1/groups/g1")));
});

test("nobody deletes a group someone else is still in", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  for (const uid of ["u1", "u3"]) {
    const db = studentDb(uid);
    const batch = writeBatch(db);
    batch.delete(doc(db, "events/e1/groups/g1"));
    batch.delete(doc(db, `events/e1/groupMembers/${uid}`));
    await assertFails(batch.commit());
  }
  await expectMembers("e1", "g1", ["u2", "u1"]);
});

// -- markers --

test("a student reads their own marker, before and after it exists", async () => {
  const db = studentDb("u1");
  await assertSucceeds(getDoc(doc(db, "events/e1/groupMembers/u1")));
  await seedGroup("e1", "g1", ["u1"]);
  await assertSucceeds(getDoc(doc(db, "events/e1/groupMembers/u1")));
});

test("a student can't read or list other students' markers", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, "events/e1/groupMembers/u2")));
  await assertFails(getDocs(collection(db, "events/e1/groupMembers")));
});

test("a student can't create a marker for a group they aren't in", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "events/e1/groupMembers/u1"), { groupId: "g1" }));
  await assertFails(setDoc(doc(db, "events/e1/groupMembers/u1"), { groupId: "missing" }));
});

test("a student can't create another student's marker", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u2", "u1"], (batch, marker) => {
    batch.set(marker("u1"), { groupId: "g1" });
    batch.set(marker("u3"), { groupId: "g1" });
  }));
});

for (const [name, marker] of [
  ["another field", { groupId: "g1", joinedAt: serverTimestamp() }],
  ["a groupId that isn't text", { groupId: 1 }],
  ["a groupId that is a path", { groupId: "g1/messages/m1" }],
]) {
  test(`a marker with ${name} is denied`, async () => {
    await seedGroup("e1", "g1", ["u2"]);
    const db = studentDb("u1");
    const batch = writeBatch(db);
    batch.update(doc(db, "events/e1/groups/g1"), { memberUids: ["u2", "u1"] });
    batch.set(doc(db, "events/e1/groupMembers/u1"), marker);
    await assertFails(batch.commit());
  });
}

test("nobody changes a marker, so a student can't switch groups without leaving", async () => {
  await seedGroup("e1", "g1", ["u1"]);
  await seedGroup("e1", "g2", ["u2"]);
  const db = studentDb("u1");
  await assertFails(updateDoc(doc(db, "events/e1/groupMembers/u1"), { groupId: "g2" }));
  await assertFails(writeMembers(db, "e1", "g2", ["u2", "u1"], (batch, marker) => {
    batch.set(marker("u1"), { groupId: "g2" });
  }));
});

test("a member can't delete their marker while staying in the group", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await assertFails(deleteDoc(doc(studentDb("u1"), "events/e1/groupMembers/u1")));
});

test("a student can't delete another student's marker", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await assertFails(writeMembers(studentDb("u1"), "e1", "g1", ["u1"], (batch, marker) => {
    batch.delete(marker("u2"));
  }));
  await assertFails(deleteDoc(doc(studentDb("u1"), "events/e1/groupMembers/u2")));
});

test("a student deletes a marker whose group no longer has them", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "events/e1/groupMembers/u1"), { groupId: "gone" });
  });
  await assertSucceeds(deleteDoc(doc(studentDb("u1"), "events/e1/groupMembers/u1")));
});

// ---- events/{eventId}/groups/{groupId}/messages/{id} (#237) ----
// A message has exactly senderId (the sender's UID), text (1 to 1000 characters) and the
// server's createdAt. Only the group's members read and send; nobody edits or deletes one.

/** A message as the app sends it: [uid] as its sender, [text], the server's time. */
function newMessage(uid, text = "See you at 6?") {
  return { senderId: uid, text, createdAt: serverTimestamp() };
}

function messagePath(eventId, groupId, id) {
  return `events/${eventId}/groups/${groupId}/messages/${id}`;
}

/** Sends [message] as a new document in a transaction, the way sendMessage does. */
function sendAs(db, eventId, groupId, message, id = "new") {
  return runTransaction(db, async (transaction) => {
    transaction.set(doc(db, messagePath(eventId, groupId, id)), message);
  });
}

/** The query FirestoreMessageRepository.observeMessages listens to. */
function chatOf(db, eventId, groupId) {
  return query(
    collection(db, `events/${eventId}/groups/${groupId}/messages`), orderBy("createdAt"),
  );
}

/** Stores the message [id] from [senderId] at [millis], rules off. */
async function seedMessage(eventId, groupId, id, senderId, millis = 1_790_000_000_000) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), messagePath(eventId, groupId, id)), {
      senderId, text: "Seeded", createdAt: Timestamp.fromMillis(millis),
    });
  });
}

/** The stored message [id], or null without it, rules off. */
async function storedMessage(eventId, groupId, id) {
  let message;
  await env.withSecurityRulesDisabled(async (context) => {
    const snapshot = await getDoc(doc(context.firestore(), messagePath(eventId, groupId, id)));
    message = snapshot.exists() ? snapshot.data() : null;
  });
  return message;
}

/** Fails if the message [id] was stored. */
async function expectNoMessage(eventId, groupId, id = "new") {
  const message = await storedMessage(eventId, groupId, id);
  if (message !== null) throw new Error(`Expected no message, got ${JSON.stringify(message)}`);
}

// -- reading --

test("a member reads their group's chat in the server's time order, as the app does", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await seedMessage("e1", "g1", "later", "u2", 1_790_000_060_000);
  await seedMessage("e1", "g1", "earlier", "u1", 1_790_000_000_000);
  const db = studentDb("u1");
  await assertSucceeds(getDoc(doc(db, messagePath("e1", "g1", "later"))));
  const chat = await assertSucceeds(getDocs(chatOf(db, "e1", "g1")));
  const ids = chat.docs.map((message) => message.id);
  if (ids.join(",") !== "earlier,later") throw new Error(`Unexpected order: ${ids}`);
});

test("a student who isn't in the group can't read its chat, even if they can read the event", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  await seedMessage("e1", "g1", "m1", "u2");
  const db = studentDb("u1");
  await assertSucceeds(getDoc(doc(db, "events/e1/groups/g1")));
  await assertFails(getDoc(doc(db, messagePath("e1", "g1", "m1"))));
  await assertFails(getDocs(chatOf(db, "e1", "g1")));
});

test("a member of another group of the event can't read or send in this one", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  await seedGroup("e1", "g2", ["u1"]);
  await seedMessage("e1", "g1", "m1", "u2");
  const db = studentDb("u1");
  await assertSucceeds(getDocs(chatOf(db, "e1", "g2")));
  await assertFails(getDoc(doc(db, messagePath("e1", "g1", "m1"))));
  await assertFails(getDocs(chatOf(db, "e1", "g1")));
  await assertFails(sendAs(db, "e1", "g1", newMessage("u1")));
  await expectNoMessage("e1", "g1");
});

test("nobody reads or sends in the chat of a group that doesn't exist", async () => {
  await seedMessage("e1", "gone", "m1", "u1");
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, messagePath("e1", "gone", "m1"))));
  await assertFails(getDocs(chatOf(db, "e1", "gone")));
  await assertFails(sendAs(db, "e1", "gone", newMessage("u1")));
});

test("a member who left their group can no longer read or send in its chat", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await seedMessage("e1", "g1", "m1", "u1");
  await assertSucceeds(leaveAs(studentDb("u1"), "u1", "e1", "g1"));
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, messagePath("e1", "g1", "m1"))));
  await assertFails(getDocs(chatOf(db, "e1", "g1")));
  await assertFails(sendAs(db, "e1", "g1", newMessage("u1")));
});

test("a member removed from a private event keeps their group's chat, like the group", async () => {
  await seedGroup("private", "g1", ["u2", "u3"]);
  await seedMessage("private", "g1", "m1", "u2");
  await removeU3FromPrivateEvent();
  const db = studentDb("u3");
  await assertSucceeds(getDocs(chatOf(db, "private", "g1")));
  await assertSucceeds(sendAs(db, "private", "g1", newMessage("u3")));
});

test("nobody lists messages across groups", async () => {
  await seedGroup("e1", "g1", ["u1"]);
  await seedMessage("e1", "g1", "m1", "u1");
  const db = studentDb("u1");
  await assertFails(getDocs(collectionGroup(db, "messages")));
  await assertFails(getDocs(query(collectionGroup(db, "messages"), where("senderId", "==", "u1"))));
});

for (const verified of [false, true]) {
  const kind = verified ? "verified" : "unverified";
  const clubUid = verified ? "verifiedClub" : "club";
  for (const [name, uid, db, seed] of [
    ["a non-EPFL association", clubUid, () => associationDb(clubUid), async () => {}],
    ["an @epfl.ch association", "epflClub", () => epflAssociationDb("epflClub"),
      () => seedEpflAssociation("epflClub", verified)],
  ]) {
    test(`${name} (${kind}) listed in a group can't read or send in its chat`, async () => {
      await seed();
      for (const eventId of ["e1", "clubPublic"]) {
        await seedGroup(eventId, "g1", [uid, "u2"]);
        await seedMessage(eventId, "g1", "m1", "u2");
        await assertFails(getDoc(doc(db(), messagePath(eventId, "g1", "m1"))));
        await assertFails(getDocs(chatOf(db(), eventId, "g1")));
        await assertFails(sendAs(db(), eventId, "g1", newMessage(uid)));
        await expectNoMessage(eventId, "g1");
      }
    });
  }
}

for (const [name, db] of [
  ["an unverified @epfl.ch member", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a member without an email_verified claim",
    () => env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore()],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't read or send in a group's chat`, async () => {
    await seedGroup("e1", "g1", ["u1", "u2"]);
    await seedMessage("e1", "g1", "m1", "u2");
    await assertFails(getDoc(doc(db(), messagePath("e1", "g1", "m1"))));
    await assertFails(getDocs(chatOf(db(), "e1", "g1")));
    await assertFails(sendAs(db(), "e1", "g1", newMessage("u1")));
    await expectNoMessage("e1", "g1");
  });
}

// -- sending --

test("a member sends their own message in a transaction, as the app does", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await assertSucceeds(sendAs(studentDb("u1"), "e1", "g1", newMessage("u1")));
  const stored = await storedMessage("e1", "g1", "new");
  if (stored.senderId !== "u1" || stored.text !== "See you at 6?") {
    throw new Error(`Unexpected message: ${JSON.stringify(stored)}`);
  }
  const reply = doc(studentDb("u2"), messagePath("e1", "g1", "reply"));
  await assertSucceeds(setDoc(reply, newMessage("u2", "Yes!")));
});

test("a student who isn't in the group can't send to it", async () => {
  await seedGroup("e1", "g1", ["u2"]);
  await assertFails(sendAs(studentDb("u1"), "e1", "g1", newMessage("u1")));
  await expectNoMessage("e1", "g1");
});

test("a member can't send a message as another member", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await assertFails(sendAs(studentDb("u1"), "e1", "g1", newMessage("u2")));
  await expectNoMessage("e1", "g1");
});

// deleteAccount deletes the profiles and writes the deletion marker, but leaves the UID in
// memberUids, and the deleted user's ID token stays valid for up to an hour.
test("a deleted account can't send to a group it is still listed in", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await seedDeletedAccount("u1");
  await assertFails(sendAs(studentDb("u1"), "e1", "g1", newMessage("u1")));
  await expectNoMessage("e1", "g1");
});

test("another member's deletion marker doesn't stop a member sending", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await seedDeletedAccount("u2");
  await assertSucceeds(sendAs(studentDb("u1"), "e1", "g1", newMessage("u1")));
});

for (const [name, changes] of [
  ["an empty text", { text: "" }],
  ["a text of 1001 characters", { text: "a".repeat(1001) }],
  ["501 emoji (1002 UTF-16 units)", { text: "\u{1F600}".repeat(501) }],
  ["a text that isn't a string", { text: 42 }],
  ["a null text", { text: null }],
  ["no text", { text: undefined }],
  ["no sender", { senderId: undefined }],
  ["a createdAt the client chose", { createdAt: Timestamp.fromMillis(1_790_000_000_000) }],
  ["no createdAt", { createdAt: undefined }],
  ["another field", { editedAt: serverTimestamp() }],
]) {
  test(`a message with ${name} is denied`, async () => {
    await seedGroup("e1", "g1", ["u1"]);
    const message = { ...newMessage("u1"), ...changes };
    for (const key of Object.keys(message)) {
      if (message[key] === undefined) delete message[key];
    }
    await assertFails(sendAs(studentDb("u1"), "e1", "g1", message));
    await expectNoMessage("e1", "g1");
  });
}

test("the text limit allows 1 and 1000 characters, and 500 emoji", async () => {
  await seedGroup("e1", "g1", ["u1"]);
  const db = studentDb("u1");
  await assertSucceeds(sendAs(db, "e1", "g1", newMessage("u1", "a"), "shortest"));
  await assertSucceeds(sendAs(db, "e1", "g1", newMessage("u1", "a".repeat(1000)), "longest"));
  await assertSucceeds(sendAs(db, "e1", "g1", newMessage("u1", "\u{1F600}".repeat(500)), "emoji"));
});

// -- nobody edits or deletes --

test("nobody edits or deletes a message, not even its sender", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await seedMessage("e1", "g1", "m1", "u1");
  for (const uid of ["u1", "u2"]) {
    const message = doc(studentDb(uid), messagePath("e1", "g1", "m1"));
    await assertFails(updateDoc(message, { text: "Edited" }));
    await assertFails(setDoc(message, newMessage("u1", "Overwritten")));
    await assertFails(deleteDoc(message));
  }
  const stored = await storedMessage("e1", "g1", "m1");
  if (stored.text !== "Seeded") throw new Error(`The message changed: ${JSON.stringify(stored)}`);
});
