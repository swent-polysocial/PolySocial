// Contributors: OpenAI Codex (deleted-token chat reads and immutable deleted authors, #241).
// Contributors: Claude (rules tests for #33, and firebase/package.json, which can't hold a comment; edge-case, operation and path coverage, #33; users/{uid} tests, no catch-all, batches, transactions, queries, flag edge cases, uid paths, other paths, and fixed uid and accountType, #35; @epfl.ch association tests, accountType values on create and association profile deletes, #161; associations/{uid} tests, #126, with more from a test audit and a review; displayName limit, written test-first, #143; publicProfiles/{uid} tests, written test-first, #229; reads by ID only and the fixed section and year lists, after review, #229; private edits keep the public copy, and deleted accounts stay deleted, after review, #229; interests from the fixed list, test-first, #230; any code read in the list check, after review, #230); OpenAI Codex (event visibility and query tests, #52); Claude Opus 5.5 (event write tests, #47; badge, locationName, profile-delete and organizer-reader tests after review; registration tests, #121; deleted accounts can't register, #275); Claude (Opus 5.5, Anthropic) (event group and groupMembers marker tests, #232, with more after review; group chat message tests, #237, and a deleted account's messages, after review).
// Contributors: Claude (Opus 5.5, Anthropic) (a non-EPFL association in allowedUids can't run My events' private query, #320).
// Contributors: Claude (Fable 5.2, Anthropic) (other shapes and accounts denied the own-groups query, and the latest-message query of the chat list, #363; groups documents outside an event, and more accounts and membership changes on the latest-message query, from a test audit).
// Contributors: Claude (Opus 5.5, Anthropic) (co-organizer tests, written test-first, #370).
// Contributors: Claude (Opus 5.5, Anthropic) (username tests, #448, and more from #517's review).
// Contributors: Claude (Opus 5.5, Anthropic) (attendeeCount rows and the counted registration writes, #373, from #372's prototype by Claude (Fable 5.2 and Opus 5.5, Anthropic)).
// Contributors: Claude (Opus 5.5, Anthropic) (check-in tests, written test-first, #467).
// Contributors: Claude (Opus 5.5, Anthropic) (entrance code and QR check-in tests, written test-first, #468).
// Contributors: Claude (Opus 5.5, Anthropic) (one-to-one chat tests and the message helpers both chats share, #428, from #365's prototype tests by Claude (Fable 5.2 and Opus 5.5, Anthropic); the refused messages both chats share, and cases a mutation check found missing, from a test review, #428).
const { after, before, beforeEach, test } = require("node:test");
// Contributors: OpenAI Codex (deleted-token membership creation/join regression, #241).
// Contributors: Claude Opus 5.5 (friend requests involving an association, written test-first from the #416 review); Konstantinos Perdikeas (directed, reviewed and owns it).
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { appInterests, appProfileOptions, appUsernameRules } = require("../appProfileOptions");
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
  getCountFromServer,
  getDoc,
  getDocs,
  increment,
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

// Nobody is registered for an event that doesn't exist yet, so co-organizers come only with an
// update, where the rules check their registrations (#370).
test("an event is created with its creator as its only organizer", async () => {
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "events/new"), eventBy("u1", {
    isPrivate: true, organizerIds: ["u1", "u5"], allowedUids: ["u1", "u5"],
  })));
  await assertSucceeds(setDoc(doc(db, "events/new"), eventBy("u1", {
    isPrivate: true, allowedUids: ["u1", "u5"],
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
  await seedManagedEventWithAttendees();
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), {
    organizerIds: ["u2", "u5", "u3"],
  }));
});

test("an organizer added with the allowlist can read and manage a private event", async () => {
  await seedManagedEvent();
  await seedAttendee("managed", "u4");
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
  await seedManagedEventWithAttendees();
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

// ---- co-organizers (#370, decision 17) ----

/** u2 created "solo" and is its only organizer; u1, u3, u4 and u6 are registered for it. */
async function seedSoloEventWithAttendees() {
  await seedEvent("solo", eventBy("u2"));
  for (const uid of ["u1", "u3", "u4", "u6"]) await seedAttendee("solo", uid);
}

/** [uid]'s update of "solo"'s organizers to [organizerIds], each also an allowed reader. */
function setSoloOrganizers(uid, organizerIds) {
  return updateDoc(doc(studentDb(uid), "events/solo"), {
    organizerIds, allowedUids: organizerIds,
  });
}

test("an organizer adds a registered student, who can then manage the event", async () => {
  await seedSoloEventWithAttendees();
  await assertSucceeds(setSoloOrganizers("u2", ["u2", "u1"]));
  await assertSucceeds(updateDoc(doc(studentDb("u1"), "events/solo"), { title: "Moved" }));
});

test("an organizer can't add a student who isn't registered, even an allowed reader", async () => {
  await seedManagedEventWithAttendees();
  await assertFails(updateDoc(doc(studentDb("u2"), "events/managed"), {
    organizerIds: ["u2", "u5", "u4"], allowedUids: ["u2", "u5", "u3", "u4"],
  }));
});

test("a registration for another event doesn't make a student addable", async () => {
  await seedManagedEvent();
  await seedAttendee("other", "u3");
  await assertFails(updateDoc(doc(studentDb("u2"), "events/managed"), {
    organizerIds: ["u2", "u5", "u3"],
  }));
});

test("an organizer adds up to 4 registered students at once, the 5-organizer limit", async () => {
  await seedSoloEventWithAttendees();
  await assertSucceeds(setSoloOrganizers("u2", ["u2", "u1", "u3", "u4", "u6"]));
});

// The rules check each added organizer by its index, so an unregistered one is caught in every
// position.
for (const position of [0, 1, 2, 3]) {
  test(`adding 4 organizers is denied if the one at index ${position} isn't registered`, async () => {
    await seedSoloEventWithAttendees();
    const added = ["u1", "u3", "u4", "u6"];
    added[position] = "u7";
    await assertFails(setSoloOrganizers("u2", ["u2", ...added]));
  });
}

// Only the first 4 added are checked by index, so a fifth must be refused even when the stored
// list lacks the creator (an event written by hand, before isValidEvent required them) and
// re-adding them makes room for it.
test("adding a fifth organizer at once is denied, so it can't skip the registration check", async () => {
  await seedEvent("solo", eventBy("u2", { organizerIds: ["u5"], allowedUids: ["u2", "u5"] }));
  for (const uid of ["u2", "u1", "u3", "u4"]) await seedAttendee("solo", uid);
  await assertFails(setSoloOrganizers("u5", ["u2", "u1", "u3", "u4", "u7"]));
});

test("a sixth organizer is denied, even a registered student", async () => {
  await seedSoloEventWithAttendees();
  await seedAttendee("solo", "u7");
  await assertFails(setSoloOrganizers("u2", ["u2", "u1", "u3", "u4", "u6", "u7"]));
});

test("an organizer can't be listed twice", async () => {
  await seedSoloEventWithAttendees();
  await assertFails(setSoloOrganizers("u2", ["u2", "u1", "u1"]));
});

test("a co-organizer can add another registered student", async () => {
  await seedManagedEventWithAttendees();
  await assertSucceeds(updateDoc(doc(studentDb("u5"), "events/managed"), {
    organizerIds: ["u2", "u5", "u3"],
  }));
});

// Who may remove whom, as the Sprint 6 PO decided on #400: any organizer removes any
// co-organizer, never the creator (see "an organizer can't remove the creator from the
// organizers").
for (const [name, uid] of [["the creator", "u2"], ["the co-organizer themselves", "u5"]]) {
  test(`${name} can remove a co-organizer`, async () => {
    await seedManagedEventWithAttendees();
    await assertSucceeds(updateDoc(doc(studentDb(uid), "events/managed"), {
      organizerIds: ["u2"],
    }));
  });
}

test("a removed co-organizer can no longer manage the event", async () => {
  await seedManagedEventWithAttendees();
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), {
    organizerIds: ["u2"],
  }));
  await assertFails(updateDoc(doc(studentDb("u5"), "events/managed"), { title: "Hijacked" }));
});

test("an organizer can swap a co-organizer for a registered student in one update", async () => {
  await seedManagedEventWithAttendees();
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), {
    organizerIds: ["u2", "u3"],
  }));
});

// Also decided on #400: cancelling doesn't remove a co-organizer.
test("a co-organizer who cancels their registration stays an organizer", async () => {
  await seedSoloEventWithAttendees();
  await assertSucceeds(setSoloOrganizers("u2", ["u2", "u1"]));
  await assertSucceeds(cancelCounted(studentDb("u1"), "solo", "u1"));
  await assertSucceeds(updateDoc(doc(studentDb("u1"), "events/solo"), { title: "Moved" }));
});

test("an association event keeps its single organizer, even with a registered student", async () => {
  await seedEvent("clubManaged", eventBy("verifiedClub", { isAssociationEvent: true }));
  await seedAttendee("clubManaged", "u1");
  await assertFails(updateDoc(doc(associationDb("verifiedClub"), "events/clubManaged"), {
    organizerIds: ["verifiedClub", "u1"], allowedUids: ["verifiedClub", "u1"],
  }));
});

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

/**
 * Stores [uid]'s registration for events/[eventId], bypassing the rules, and counts it in the
 * event's attendeeCount when the event exists, as every registration is (#372).
 */
async function seedAttendee(eventId, uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, `events/${eventId}/attendees/${uid}`), {
      uid, registeredAt: Timestamp.fromMillis(1_790_000_000_000),
    });
    if ((await getDoc(doc(db, `events/${eventId}`))).exists()) {
      await updateDoc(doc(db, `events/${eventId}`), { attendeeCount: increment(1) });
    }
  });
}

/**
 * The counted registration (#372): [uid]'s registration, holding [data], and the event's
 * attendeeCount raised by one, in one write, as the app sends it.
 */
function registerCounted(db, eventId, uid, data = registration(uid)) {
  const batch = writeBatch(db);
  batch.set(doc(db, `events/${eventId}/attendees/${uid}`), data);
  batch.update(doc(db, `events/${eventId}`), { attendeeCount: increment(1) });
  return batch.commit();
}

/** The counted cancellation (#372): [uid]'s registration deleted and the counter lowered by one. */
function cancelCounted(db, eventId, uid) {
  const batch = writeBatch(db);
  batch.delete(doc(db, `events/${eventId}/attendees/${uid}`));
  batch.update(doc(db, `events/${eventId}`), { attendeeCount: increment(-1) });
  return batch.commit();
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
  // Write-only: no read of the event, so registrations don't contend on it (#372).
  await assertSucceeds(runTransaction(db, async (transaction) => {
    transaction.set(attendee, registration("u1"));
    transaction.update(doc(db, "events/e1"), { attendeeCount: increment(1) });
  }));
  await assertSucceeds(getDoc(attendee));
});

for (const uid of ["u2", "u3"]) {
  test(`a student allowed in a private event (${uid}) can register for it`, async () => {
    await assertSucceeds(registerCounted(studentDb(uid), "private", uid));
  });
}

test("a student can't register for a private event they aren't allowed in", async () => {
  await assertFails(registerCounted(studentDb("u1"), "private", "u1"));
});

test("a student can't register for an event that doesn't exist", async () => {
  await assertFails(registerCounted(studentDb("u1"), "missing", "u1"));
});

// deleteAccount writes the marker, then deletes the student's registrations, then their Auth user
// (#275). Their ID token stays valid meanwhile, so the rules must refuse a new registration.
test("a deleted account can't register again with its still-valid token", async () => {
  await seedDeletedAccount("u1");
  await assertFails(registerCounted(studentDb("u1"), "e1", "u1"));
  await assertSucceeds(registerCounted(studentDb("u3"), "e1", "u3"));
});

test("a student can't register another student", async () => {
  const db = studentDb("u1");
  await assertFails(registerCounted(db, "e1", "u3"));
  await assertFails(registerCounted(db, "e1", "u3", registration("u1")));
  await assertFails(registerCounted(db, "e1", "u1", registration("u3")));
  // Not even beside their own registration, on its one counted place.
  const batch = writeBatch(db);
  batch.set(doc(db, "events/e1/attendees/u1"), registration("u1"));
  batch.set(doc(db, "events/e1/attendees/u3"), registration("u3"));
  batch.update(doc(db, "events/e1"), { attendeeCount: increment(1) });
  await assertFails(batch.commit());
  await assertSucceeds(registerCounted(db, "e1", "u1"));
});

for (const [name, data] of [
  ["no uid", { registeredAt: serverTimestamp() }],
  ["no registeredAt", { uid: "u1" }],
  ["a registeredAt the client chose", { uid: "u1", registeredAt: Timestamp.fromMillis(0) }],
  ["another field", { ...registration("u1"), displayName: "Student One" }],
]) {
  test(`a registration with ${name} is denied`, async () => {
    await assertFails(registerCounted(studentDb("u1"), "e1", "u1", data));
    await assertSucceeds(registerCounted(studentDb("u1"), "e1", "u1"));
  });
}

test("nobody can change a registration, not even its student", async () => {
  await seedAttendee("e1", "u1");
  const db = studentDb("u1");
  await assertFails(updateDoc(doc(db, "events/e1/attendees/u1"), {
    registeredAt: serverTimestamp(),
  }));
  await assertFails(registerCounted(db, "e1", "u1"));
});

for (const [name, uid, db] of [
  ["a verified association", "verifiedClub", () => associationDb("verifiedClub")],
  ["an unverified association", "club", () => associationDb("club")],
  ["an unverified @epfl.ch user", "u1", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a signed-out user", "u1", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't register or read registrations`, async () => {
    // Before any registration exists under their UID, so only the account is wrong.
    await assertFails(registerCounted(db(), "e1", uid));
    await assertFails(registerCounted(db(), "clubPublic", uid));
    await seedAttendee("e1", "u1");
    await assertFails(getDoc(doc(db(), "events/e1/attendees/u1")));
    await assertFails(getDocs(query(collectionGroup(db(), "attendees"), where("uid", "==", uid))));
  });
}

test("an @epfl.ch association can't register as a student", async () => {
  await seedEpflAssociation("epflClub", true);
  await assertFails(registerCounted(epflAssociationDb("epflClub"), "e1", "epflClub"));
});

test("a student can cancel their own registration, and cancel again", async () => {
  await seedAttendee("e1", "u1");
  const db = studentDb("u1");
  await assertSucceeds(cancelCounted(db, "e1", "u1"));
  // Nothing is left to count: the counted cancellation is refused, the delete alone accepted.
  await assertFails(cancelCounted(db, "e1", "u1"));
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
    await assertFails(registerCounted(studentDb("u1"), "ended", "u1"));
  });
}

for (const [name, times] of [
  ["that has started but not ended", { startTime: minutesFromNow(-60), endTime: minutesFromNow(60) }],
  ["without an endTime, before it starts", { startTime: minutesFromNow(60), endTime: null }],
]) {
  test(`a student can register for an event ${name}`, async () => {
    await seedEvent("running", eventBy("u2", times));
    await assertSucceeds(registerCounted(studentDb("u1"), "running", "u1"));
  });
}

test("a student can still cancel once the event has ended", async () => {
  await seedEvent("ended", eventBy("u2", { startTime: minutesFromNow(-120), endTime: minutesFromNow(-1) }));
  await seedAttendee("ended", "u1");
  await assertSucceeds(cancelCounted(studentDb("u1"), "ended", "u1"));
});

test("a student can cancel in a transaction, as the app does", async () => {
  await seedAttendee("e1", "u1");
  const db = studentDb("u1");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    transaction.delete(doc(db, "events/e1/attendees/u1"));
    transaction.update(doc(db, "events/e1"), { attendeeCount: increment(-1) });
  }));
});

test("a student can still cancel once the event is deleted or no longer readable", async () => {
  await seedAttendee("gone", "u1");
  await seedEvent("closed", eventBy("u2", { isPrivate: true }));
  await seedAttendee("closed", "u1");
  const db = studentDb("u1");
  // No counter to lower (the event is gone) or none they may lower (they can't read the event).
  await assertFails(cancelCounted(db, "gone", "u1"));
  await assertFails(cancelCounted(db, "closed", "u1"));
  await assertSucceeds(deleteDoc(doc(db, "events/gone/attendees/u1")));
  await assertSucceeds(deleteDoc(doc(db, "events/closed/attendees/u1")));
});

test("a student can't cancel another student's registration, even as an organizer", async () => {
  await seedManagedEventWithAttendees();
  for (const uid of ["u3", "u2"]) {
    const db = studentDb(uid);
    await assertFails(deleteDoc(doc(db, "events/managed/attendees/u1")));
    const batch = writeBatch(db);
    batch.delete(doc(db, "events/managed/attendees/u1"));
    batch.update(doc(db, "events/managed"), { attendeeCount: increment(-1) });
    await assertFails(batch.commit());
  }
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

// ---- events/{id}.attendeeCount (#373, decided in #372, decision 21) ----
// One test per row of #372's tables (C1–C5, O1–O10, R1–R28, X1–X13, L1–L2), named after its row.
// A counted registration is the caller's own attendees/{uid} created and the event's
// attendeeCount raised by one in the same write; a counted cancellation deletes it and lowers the
// counter by one. The app sends the change as increment(±1), and so do the rows unless they say
// otherwise. C5, O10 and R24 need a decimal number, which the JS SDK never sends for a whole
// one, so they go through the emulator's REST API (at the end of this section).

/** An upcoming event created by [uid], with [overrides]. */
function upcomingEventBy(uid, overrides = {}) {
  return eventBy(uid, { startTime: FAR_FUTURE, ...overrides });
}

/** Stores [uid]'s registration for events/[eventId] without counting it, bypassing the rules. */
async function seedUncountedAttendee(eventId, uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `events/${eventId}/attendees/${uid}`), {
      uid, registeredAt: Timestamp.fromMillis(1_790_000_000_000),
    });
  });
}

/** An upcoming event by u2 with [count] registered students (u10, u11, …), counted. */
async function seedEventWithCount(id, count, overrides = {}) {
  await seedEvent(id, upcomingEventBy("u2", { attendeeCount: count, ...overrides }));
  for (let i = 0; i < count; i++) await seedUncountedAttendee(id, `u${10 + i}`);
}

/** The stored event's attendeeCount ("no event" when it is missing) and its attendees, sorted. */
async function storedCount(eventId) {
  let result;
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const event = await getDoc(doc(db, `events/${eventId}`));
    const attendees = await getDocs(collection(db, `events/${eventId}/attendees`));
    result = {
      count: event.exists() ? event.data().attendeeCount : "no event",
      attendees: attendees.docs.map((attendee) => attendee.id).sort(),
    };
  });
  return result;
}

async function expectStored(eventId, count, attendees) {
  assert.deepEqual(await storedCount(eventId), { count, attendees });
}

/**
 * One batch as [uid]: optionally creates or deletes their registration (under [attendeeEvent],
 * holding [attendeeData]), and applies [eventChange] to events/[eventId].
 */
function registrationWrite(db, eventId, uid, {
  attendee = null, eventChange = null, attendeeEvent = eventId, attendeeData = registration(uid),
}) {
  const batch = writeBatch(db);
  const own = doc(db, `events/${attendeeEvent}/attendees/${uid}`);
  if (attendee === "create") batch.set(own, attendeeData);
  if (attendee === "delete") batch.delete(own);
  if (eventChange) batch.update(doc(db, `events/${eventId}`), eventChange);
  return batch.commit();
}

/** Whether a write was allowed: true once committed, false when the rules refused it. */
function committed(write) {
  return write.then(() => true, (error) => {
    if (error.code === "permission-denied") return false;
    throw error;
  });
}

/**
 * [uid] registers as FirestoreRegistrationRepository.register does (#373): one transaction with
 * no read, the registration and increment(1), with the SDK's default attempts.
 */
function registersAsApp(uid, eventId) {
  const db = studentDb(uid);
  return committed(runTransaction(db, async (transaction) => {
    transaction.set(doc(db, `events/${eventId}/attendees/${uid}`), registration(uid));
    transaction.update(doc(db, `events/${eventId}`), { attendeeCount: increment(1) });
  }));
}

/** [uid] cancels as the repository does first: one transaction with no read, increment(-1). */
function cancelsAsApp(uid, eventId) {
  const db = studentDb(uid);
  return committed(runTransaction(db, async (transaction) => {
    transaction.delete(doc(db, `events/${eventId}/attendees/${uid}`));
    transaction.update(doc(db, `events/${eventId}`), { attendeeCount: increment(-1) });
  }));
}

/** The repository's fallback cancel: [uid]'s registration deleted alone, in a transaction. */
function cancelsAloneAsApp(uid, eventId) {
  const db = studentDb(uid);
  return committed(runTransaction(db, async (transaction) => {
    transaction.delete(doc(db, `events/${eventId}/attendees/${uid}`));
  }));
}

// Creating an event

test("C1 a student creates an event with attendeeCount 0", async () => {
  await assertSucceeds(
    setDoc(doc(studentDb("u1"), "events/new"), upcomingEventBy("u1", { attendeeCount: 0 })),
  );
});

test("C2 an event created without attendeeCount is allowed (an app from before the counter)", async () => {
  await assertSucceeds(setDoc(doc(studentDb("u1"), "events/new"), upcomingEventBy("u1")));
});

for (const [name, value] of [
  ["1", 1], ["-1", -1], ["null", null], ['"0"', "0"], ["0.5", 0.5], ["a list", [0]],
]) {
  test(`C3 an event created with attendeeCount ${name} is denied`, async () => {
    await assertFails(
      setDoc(doc(studentDb("u1"), "events/new"), upcomingEventBy("u1", { attendeeCount: value })),
    );
  });
}

test("C4 a verified association creates its event with attendeeCount 0, not 3", async () => {
  const db = associationDb("verifiedClub");
  const event = upcomingEventBy("verifiedClub", { isAssociationEvent: true });
  await assertFails(setDoc(doc(db, "events/club1"), { ...event, attendeeCount: 3 }));
  await assertSucceeds(setDoc(doc(db, "events/club1"), { ...event, attendeeCount: 0 }));
});

// An organizer's edits

test("O1 an organizer's edit keeps attendeeCount", async () => {
  await seedEventWithCount("managed", 3, { capacity: 10 });
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), {
    title: "Moved to Rolex", capacity: 12,
  }));
  await expectStored("managed", 3, ["u10", "u11", "u12"]);
});

for (const [name, change] of [
  ["set attendeeCount", { attendeeCount: 0 }],
  ["raise attendeeCount by one", { attendeeCount: 4 }],
  ["lower attendeeCount by one", { attendeeCount: 2 }],
  ["raise attendeeCount with increment(1)", { attendeeCount: increment(1) }],
  ["change attendeeCount with another field", { title: "Renamed", attendeeCount: 4 }],
  ["remove attendeeCount", { attendeeCount: deleteField() }],
]) {
  test(`O2 an organizer can't ${name}`, async () => {
    await seedEventWithCount("managed", 3, { capacity: 10 });
    await assertFails(updateDoc(doc(studentDb("u2"), "events/managed"), change));
    await expectStored("managed", 3, ["u10", "u11", "u12"]);
  });
}

test("O3 an organizer can't add attendeeCount to an event that has none", async () => {
  await seedEvent("legacy", upcomingEventBy("u2"));
  await assertFails(updateDoc(doc(studentDb("u2"), "events/legacy"), { attendeeCount: 0 }));
  await assertFails(updateDoc(doc(studentDb("u2"), "events/legacy"), { attendeeCount: 5 }));
});

test("O4 an organizer overwriting the event without attendeeCount is denied; with the stored value, allowed", async () => {
  await seedEventWithCount("managed", 3, { capacity: 10 });
  const db = studentDb("u2");
  await assertFails(setDoc(doc(db, "events/managed"), upcomingEventBy("u2", { title: "Rewritten" })));
  await assertSucceeds(setDoc(
    doc(db, "events/managed"), upcomingEventBy("u2", { title: "Rewritten", attendeeCount: 3 }),
  ));
});

test("O5 an organizer may lower the capacity below the count: nobody new registers, students still cancel", async () => {
  await seedEventWithCount("managed", 3, { capacity: 10 });
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), { capacity: 2 }));
  assert.equal(await registersAsApp("u1", "managed"), false);
  assert.equal(await cancelsAsApp("u10", "managed"), true);
  await expectStored("managed", 2, ["u11", "u12"]);
  // Still full at 2 of 2.
  assert.equal(await registersAsApp("u1", "managed"), false);
});

test("O6 a verified association can't change its own event's attendeeCount", async () => {
  await seedEvent(
    "clubEvent", upcomingEventBy("verifiedClub", { isAssociationEvent: true, attendeeCount: 2 }),
  );
  const db = associationDb("verifiedClub");
  await assertFails(updateDoc(doc(db, "events/clubEvent"), { attendeeCount: 3 }));
  await assertFails(updateDoc(doc(db, "events/clubEvent"), { attendeeCount: 1 }));
  await assertSucceeds(updateDoc(doc(db, "events/clubEvent"), { title: "Jam" }));
});

test("O7 an organizer who is a student registers for their own event like any student", async () => {
  await seedEventWithCount("managed", 1, { capacity: 2 });
  assert.equal(await registersAsApp("u2", "managed"), true);
  await expectStored("managed", 2, ["u10", "u2"]);
  // …but can't give themselves a second place, or free one, as an organizer.
  await assertFails(updateDoc(doc(studentDb("u2"), "events/managed"), { attendeeCount: 1 }));
});

test("O8 an organizer can't register and edit the event in one write", async () => {
  await seedEventWithCount("managed", 1, { capacity: 5 });
  await assertFails(registrationWrite(studentDb("u2"), "managed", "u2", {
    attendee: "create", eventChange: { attendeeCount: increment(1), title: "Renamed" },
  }));
  await expectStored("managed", 1, ["u10"]);
});

test("O9 an organizer deletes an event that has registrations: the counter goes with it", async () => {
  await seedEventWithCount("managed", 3);
  await assertSucceeds(deleteDoc(doc(studentDb("u2"), "events/managed")));
  await expectStored("managed", "no event", ["u10", "u11", "u12"]);
});

// Registering

test("R1 a student registers as the app does: a write-only transaction, registration and increment(1)", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  assert.equal(await registersAsApp("u1", "open"), true);
  await expectStored("open", 4, ["u1", "u10", "u11", "u12"]);
});

test("R2 registering for an event without a counter (and without organizerIds) starts it at 1", async () => {
  assert.equal(await registersAsApp("u1", "e1"), true);
  await expectStored("e1", 1, ["u1"]);
});

test("R3 registering at capacity - 1 is allowed, and at capacity denied", async () => {
  await seedEventWithCount("small", 1, { capacity: 2 });
  await assertSucceeds(registerCounted(studentDb("u1"), "small", "u1"));
  await expectStored("small", 2, ["u1", "u10"]);
  await assertFails(registerCounted(studentDb("u3"), "small", "u3"));
  await expectStored("small", 2, ["u1", "u10"]);
});

for (const [name, overrides] of [
  ["a null capacity", { capacity: null }],
  ["no capacity field", {}],
]) {
  test(`R4 an event with ${name} takes registrations whatever the count, and still counts them`, async () => {
    const event = upcomingEventBy("u2", { attendeeCount: 1000, ...overrides });
    if (!("capacity" in overrides)) delete event.capacity;
    await seedEvent("unlimited", event);
    assert.equal(await registersAsApp("u1", "unlimited"), true);
    await expectStored("unlimited", 1001, ["u1"]);
  });
}

for (const [name, capacity] of [['a string ("2")', "2"], ["a list", [2]], ["a boolean", true]]) {
  test(`R5 a capacity that is ${name} is no limit`, async () => {
    await seedEvent("odd", upcomingEventBy("u2", { attendeeCount: 5, capacity }));
    await assertSucceeds(registerCounted(studentDb("u1"), "odd", "u1"));
    await expectStored("odd", 6, ["u1"]);
  });
}

test("R6 a decimal capacity (2.5) limits like a number: 2 fits, 3 doesn't", async () => {
  await seedEvent("odd", upcomingEventBy("u2", { attendeeCount: 1, capacity: 2.5 }));
  await assertSucceeds(registerCounted(studentDb("u1"), "odd", "u1"));
  await assertFails(registerCounted(studentDb("u3"), "odd", "u3"));
  await expectStored("odd", 2, ["u1"]);
});

for (const capacity of [0, -3, 1]) {
  test(`R7 a capacity of ${capacity} (the form asks for at least 2) is still a limit`, async () => {
    await seedEvent("odd", upcomingEventBy("u2", { attendeeCount: 1, capacity }));
    await seedUncountedAttendee("odd", "u10");
    await assertFails(registerCounted(studentDb("u1"), "odd", "u1"));
    assert.equal(await cancelsAsApp("u10", "odd"), true);
    await expectStored("odd", 0, []);
  });
}

for (const [row, name, change] of [
  ["R8", "raises the counter by 2", { attendeeCount: 5 }],
  ["R8", "raises the counter by 2 with increment(2)", { attendeeCount: increment(2) }],
  ["R8", "leaves the counter unchanged", { attendeeCount: 3 }],
  ["R8", "lowers the counter", { attendeeCount: 2 }],
  ["R8", "sets the counter to a string", { attendeeCount: "4" }],
  ["R8", "removes the counter", { attendeeCount: deleteField() }],
  ["R9", "also changes the title", { attendeeCount: increment(1), title: "Hijacked" }],
  ["R9", "also raises the capacity", { attendeeCount: increment(1), capacity: 99 }],
  ["R9", "also adds them to allowedUids", { attendeeCount: increment(1), allowedUids: ["u2", "u1"] }],
  ["R9", "also makes them an organizer", {
    attendeeCount: increment(1), organizerIds: ["u2", "u1"], allowedUids: ["u2", "u1"],
  }],
]) {
  test(`${row} a registration whose event write ${name} is denied`, async () => {
    await seedEventWithCount("open", 3, { capacity: 10 });
    await assertFails(
      registrationWrite(studentDb("u1"), "open", "u1", { attendee: "create", eventChange: change }),
    );
    await expectStored("open", 3, ["u10", "u11", "u12"]);
  });
}

test("R10 raising the counter without creating the caller's registration is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  const db = studentDb("u1");
  await assertFails(updateDoc(doc(db, "events/open"), { attendeeCount: 4 }));
  await assertFails(updateDoc(doc(db, "events/open"), { attendeeCount: increment(1) }));
  // Another student's registration in the same write doesn't count (and can't be written anyway).
  const batch = writeBatch(db);
  batch.set(doc(db, "events/open/attendees/u3"), registration("u3"));
  batch.update(doc(db, "events/open"), { attendeeCount: increment(1) });
  await assertFails(batch.commit());
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

test("R11 a student who writes the counter back unchanged, or any other field, is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  const db = studentDb("u1");
  await assertFails(updateDoc(doc(db, "events/open"), { attendeeCount: 3 }));
  await assertFails(updateDoc(doc(db, "events/open"), { title: "Hijacked" }));
  await assertFails(updateDoc(doc(db, "events/open"), { capacity: 99 }));
  await assertFails(deleteDoc(doc(db, "events/open")));
});

test("R12 a registration without the counter is denied (an app from before the counter)", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  await assertFails(setDoc(doc(studentDb("u1"), "events/open/attendees/u1"), registration("u1")));
  // Also on an event without the counter field.
  await assertFails(setDoc(doc(studentDb("u1"), "events/e1/attendees/u1"), registration("u1")));
});

test("R13 already registered, also on a full event: a second registration is denied, nothing changes", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  assert.equal(await registersAsApp("u1", "open"), true);
  assert.equal(await registersAsApp("u1", "open"), false);
  await assertFails(updateDoc(doc(studentDb("u1"), "events/open"), { attendeeCount: increment(1) }));
  await expectStored("open", 4, ["u1", "u10", "u11", "u12"]);

  await seedEventWithCount("small", 2, { capacity: 2 });
  assert.equal(await registersAsApp("u10", "small"), false);
  await expectStored("small", 2, ["u10", "u11"]);
});

test("R14 two students at once for the last place: one gets it, the other is denied", async () => {
  await seedEventWithCount("last", 1, { capacity: 2 });
  const outcomes = await Promise.all([registersAsApp("u1", "last"), registersAsApp("u3", "last")]);
  assert.deepEqual(outcomes.sort(), [false, true]);
  const stored = await storedCount("last");
  assert.equal(stored.count, 2);
  assert.equal(stored.attendees.length, 2);
});

test("R14 a count that became stale can't take the last place either", async () => {
  await seedEventWithCount("last", 1, { capacity: 2 });
  await assertSucceeds(registerCounted(studentDb("u3"), "last", "u3"));
  // u1 read 1 before u3 registered and writes 1 + 1.
  await assertFails(registrationWrite(studentDb("u1"), "last", "u1", {
    attendee: "create", eventChange: { attendeeCount: 2 },
  }));
  await assertFails(registerCounted(studentDb("u1"), "last", "u1"));
  await expectStored("last", 2, ["u10", "u3"]);
});

test("R15 two students at once with places left both register; a count that became stale is denied", async () => {
  await seedEventWithCount("roomy", 1, { capacity: 20 });
  const outcomes = await Promise.all([registersAsApp("u1", "roomy"), registersAsApp("u3", "roomy")]);
  assert.deepEqual(outcomes, [true, true]);
  await expectStored("roomy", 3, ["u1", "u10", "u3"]);
  // u4 read 1 before the two registered and writes 1 + 1.
  await assertFails(registrationWrite(studentDb("u4"), "roomy", "u4", {
    attendee: "create", eventChange: { attendeeCount: 2 },
  }));
  await expectStored("roomy", 3, ["u1", "u10", "u3"]);
});

test("R16 a counted registration that writes the stored count plus one, instead of increment(1), is allowed", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  await assertSucceeds(registrationWrite(studentDb("u1"), "open", "u1", {
    attendee: "create", eventChange: { attendeeCount: 4 },
  }));
  await expectStored("open", 4, ["u1", "u10", "u11", "u12"]);
});

test("R17 a private event: an allowed student registers, another student can't", async () => {
  await seedEvent("private", upcomingEventBy("u2", {
    isPrivate: true, allowedUids: ["u2", "u3"], capacity: 5, attendeeCount: 0,
  }));
  assert.equal(await registersAsApp("u3", "private"), true);
  await assertFails(registerCounted(studentDb("u1"), "private", "u1"));
  await assertFails(
    updateDoc(doc(studentDb("u1"), "events/private"), { attendeeCount: increment(1) }),
  );
  await expectStored("private", 1, ["u3"]);
});

test("R18 an ended event takes no registration, even counted", async () => {
  await seedEvent("ended", upcomingEventBy("u2", {
    startTime: minutesFromNow(-120), endTime: minutesFromNow(-1), attendeeCount: 0,
  }));
  await assertFails(registerCounted(studentDb("u1"), "ended", "u1"));
  await expectStored("ended", 0, []);
});

test("R19 a deleted account can't register with its still-valid token, even counted", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  await seedDeletedAccount("u1");
  await assertFails(registerCounted(studentDb("u1"), "open", "u1"));
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

for (const [name, uid, db] of [
  ["a verified association", "verifiedClub", () => associationDb("verifiedClub")],
  ["an unverified association", "club", () => associationDb("club")],
  ["an @epfl.ch association", "epflClub", () => epflAssociationDb("epflClub")],
  ["an unverified @epfl.ch user", "u1", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a verified non-EPFL user without a profile", "u7", () => firestoreAs("someone@gmail.com", true, "u7")],
  ["a signed-out user", "u1", () => env.unauthenticatedContext().firestore()],
]) {
  test(`R20 ${name} can't change attendeeCount, with or without a registration`, async () => {
    await seedEpflAssociation("epflClub", true);
    await seedEventWithCount("open", 3, { capacity: 10 });
    await assertFails(registerCounted(db(), "open", uid));
    await assertFails(updateDoc(doc(db(), "events/open"), { attendeeCount: increment(1) }));
    await assertFails(updateDoc(doc(db(), "events/open"), { attendeeCount: increment(-1) }));
    // Even with a registration seeded under their UID.
    await seedUncountedAttendee("open", uid);
    await assertFails(cancelCounted(db(), "open", uid));
    await expectStored("open", 3, ["u10", "u11", "u12", uid].sort());
  });
}

test("R21 registering for an event that doesn't exist is denied", async () => {
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "events/missing/attendees/u1"), registration("u1"));
  batch.set(doc(db, "events/missing"), { attendeeCount: 1 }, { merge: true });
  await assertFails(batch.commit());
  assert.equal(await registersAsApp("u1", "missing"), false);
  await expectStored("missing", "no event", []);
});

test("R22 a registration for one event can't raise another event's counter", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  await seedEventWithCount("other", 0, { capacity: 10 });
  // The registration is under "other", the counter change on "open".
  await assertFails(registrationWrite(studentDb("u1"), "open", "u1", {
    attendee: "create", attendeeEvent: "other", eventChange: { attendeeCount: increment(1) },
  }));
  await expectStored("open", 3, ["u10", "u11", "u12"]);
  await expectStored("other", 0, []);
});

test("R23 a student registered before the counter existed can't register again: nothing changes", async () => {
  await seedEvent("legacy", upcomingEventBy("u2", { capacity: 2 }));
  await seedUncountedAttendee("legacy", "u1");
  assert.equal(await registersAsApp("u1", "legacy"), false);
  await expectStored("legacy", undefined, ["u1"]);
});

// R24 is with the REST rows below.

test("R25 one counted place can't carry another student's registration as well", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "events/open/attendees/u1"), registration("u1"));
  batch.set(doc(db, "events/open/attendees/u9"), registration("u9"));
  batch.update(doc(db, "events/open"), { attendeeCount: increment(1) });
  await assertFails(batch.commit());
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

for (const [row, name, data] of [
  ["R26", "an extra field", { ...registration("u1"), note: "x" }],
  ["R27", "a registeredAt the client chose", { uid: "u1", registeredAt: Timestamp.fromMillis(0) }],
  ["R27", "no registeredAt", { uid: "u1" }],
  ["R28", "another student's UID in uid", registration("u9")],
  ["R28", "no uid", { registeredAt: serverTimestamp() }],
]) {
  test(`${row} a counted registration with ${name} is denied`, async () => {
    await seedEventWithCount("open", 3, { capacity: 10 });
    await assertFails(registerCounted(studentDb("u1"), "open", "u1", data));
    await expectStored("open", 3, ["u10", "u11", "u12"]);
    // The same write with the two fields the app sends is allowed.
    await assertSucceeds(registerCounted(studentDb("u1"), "open", "u1"));
  });
}

// Cancelling

test("X1 a student cancels as the app does: a write-only transaction, delete and increment(-1)", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  assert.equal(await cancelsAsApp("u10", "open"), true);
  await expectStored("open", 2, ["u11", "u12"]);
  // The place can be taken again.
  assert.equal(await registersAsApp("u1", "open"), true);
  await expectStored("open", 3, ["u1", "u11", "u12"]);
  // Writing the stored count minus one is allowed too.
  await assertSucceeds(registrationWrite(studentDb("u11"), "open", "u11", {
    attendee: "delete", eventChange: { attendeeCount: 2 },
  }));
  await expectStored("open", 2, ["u1", "u12"]);
});

test("X2 cancelling after the event ended still lowers the counter", async () => {
  await seedEventWithCount("ended", 2, {
    startTime: minutesFromNow(-120), endTime: minutesFromNow(-1),
  });
  assert.equal(await cancelsAsApp("u10", "ended"), true);
  await expectStored("ended", 1, ["u11"]);
});

test("X3 deleting a counted registration without lowering the counter is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  await assertFails(deleteDoc(doc(studentDb("u10"), "events/open/attendees/u10")));
  await assertFails(registrationWrite(studentDb("u10"), "open", "u10", {
    attendee: "delete", eventChange: { attendeeCount: 3 },
  }));
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

test("X3 the only counted registration (counter 1) deleted without lowering the counter is denied", async () => {
  // The boundary of the delete rule's `countBefore() == 0`: at 1 the place is still counted.
  await seedEventWithCount("one", 1, { capacity: 3 });
  await assertFails(deleteDoc(doc(studentDb("u10"), "events/one/attendees/u10")));
  await expectStored("one", 1, ["u10"]);
  // Counted, the same cancellation is allowed and leaves 0.
  await assertSucceeds(cancelCounted(studentDb("u10"), "one", "u10"));
  await expectStored("one", 0, []);
});

for (const [name, change] of [
  ["lowers the counter by 2", { attendeeCount: 1 }],
  ["lowers the counter by 2 with increment(-2)", { attendeeCount: increment(-2) }],
  ["raises the counter", { attendeeCount: increment(1) }],
  ["also changes the title", { attendeeCount: increment(-1), title: "Hijacked" }],
  ["also lowers the capacity", { attendeeCount: increment(-1), capacity: 2 }],
  ["removes the counter", { attendeeCount: deleteField() }],
]) {
  test(`X4 a cancellation whose event write ${name} is denied`, async () => {
    await seedEventWithCount("open", 3, { capacity: 3 });
    await assertFails(
      registrationWrite(studentDb("u10"), "open", "u10", { attendee: "delete", eventChange: change }),
    );
    await expectStored("open", 3, ["u10", "u11", "u12"]);
  });
}

test("X5 lowering the counter without deleting the caller's registration is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  // Registered, but keeps the registration.
  await assertFails(updateDoc(doc(studentDb("u10"), "events/open"), { attendeeCount: 2 }));
  await assertFails(
    updateDoc(doc(studentDb("u10"), "events/open"), { attendeeCount: increment(-1) }),
  );
  // Not registered at all (deleting a missing registration frees no place).
  await assertFails(updateDoc(doc(studentDb("u1"), "events/open"), { attendeeCount: increment(-1) }));
  await assertFails(cancelCounted(studentDb("u1"), "open", "u1"));
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

for (const [name, count] of [["absent", undefined], ["0", 0]]) {
  test(`X6 with the counter ${name}, a registration from before the counter is deleted alone, never below 0`, async () => {
    const event = upcomingEventBy("u2", count === undefined ? {} : { attendeeCount: count });
    await seedEvent("legacy", event);
    await seedUncountedAttendee("legacy", "u1");
    const db = studentDb("u1");
    await assertFails(registrationWrite(db, "legacy", "u1", {
      attendee: "delete", eventChange: { attendeeCount: -1 },
    }));
    await assertFails(cancelCounted(db, "legacy", "u1"));
    // Nor may the write that deletes it set the counter to anything else.
    await assertFails(registrationWrite(db, "legacy", "u1", {
      attendee: "delete", eventChange: { attendeeCount: 5 },
    }));
    await assertFails(registrationWrite(db, "legacy", "u1", {
      attendee: "delete", eventChange: { attendeeCount: increment(1) },
    }));
    assert.equal(await cancelsAloneAsApp("u1", "legacy"), true);
    await expectStored("legacy", count, []);
  });
}

test("X7 a registration for a deleted event is deleted alone", async () => {
  await seedUncountedAttendee("gone", "u1");
  assert.equal(await cancelsAsApp("u1", "gone"), false);
  assert.equal(await cancelsAloneAsApp("u1", "gone"), true);
  await expectStored("gone", "no event", []);
});

test("X8 a student who can no longer read the event deletes the registration alone; the counter stays", async () => {
  await seedEvent("closed", upcomingEventBy("u2", {
    isPrivate: true, allowedUids: ["u2"], attendeeCount: 1,
  }));
  await seedUncountedAttendee("closed", "u1");
  // They can't write an event they can't read, even only to lower the counter.
  await assertFails(registrationWrite(studentDb("u1"), "closed", "u1", {
    attendee: "delete", eventChange: { attendeeCount: 0 },
  }));
  assert.equal(await cancelsAsApp("u1", "closed"), false);
  assert.equal(await cancelsAloneAsApp("u1", "closed"), true);
  await expectStored("closed", 1, []);
});

test("X9 cancelling without a registration on a counted event: only the delete alone is allowed, and counts nothing", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  assert.equal(await cancelsAsApp("u1", "open"), false);
  assert.equal(await cancelsAloneAsApp("u1", "open"), true);
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

test("X10 a deleted account can still cancel with its still-valid token", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  await seedDeletedAccount("u10");
  assert.equal(await cancelsAsApp("u10", "open"), true);
  await expectStored("open", 2, ["u11", "u12"]);
});

test("X11 nobody cancels for another student, not even an organizer", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  for (const uid of ["u2", "u11"]) {
    const db = studentDb(uid);
    const batch = writeBatch(db);
    batch.delete(doc(db, "events/open/attendees/u10"));
    batch.update(doc(db, "events/open"), { attendeeCount: increment(-1) });
    await assertFails(batch.commit());
    await assertFails(deleteDoc(doc(db, "events/open/attendees/u10")));
  }
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

test("X12 two students cancelling at once both cancel; a count that became stale is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  const outcomes = await Promise.all([cancelsAsApp("u10", "open"), cancelsAsApp("u11", "open")]);
  assert.deepEqual(outcomes, [true, true]);
  await expectStored("open", 1, ["u12"]);
  // u12 read 3 before the two cancelled and writes 3 - 1.
  await assertFails(registrationWrite(studentDb("u12"), "open", "u12", {
    attendee: "delete", eventChange: { attendeeCount: 2 },
  }));
  await expectStored("open", 1, ["u12"]);
});

test("X13 a student can't free a place by deleting and creating their registration in one write", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  const db = studentDb("u10");
  const batch = writeBatch(db);
  batch.delete(doc(db, "events/open/attendees/u10"));
  batch.set(doc(db, "events/open/attendees/u10"), registration("u10"));
  batch.update(doc(db, "events/open"), { attendeeCount: increment(-1) });
  await assertFails(batch.commit());
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

// Reads, unchanged

test("L1 an organizer still lists and counts the attendees; a student still doesn't", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  const attendees = collection(studentDb("u2"), "events/open/attendees");
  const count = await assertSucceeds(getCountFromServer(attendees));
  assert.equal(count.data().count, 3);
  await assertSucceeds(getDocs(attendees));
  await assertFails(getCountFromServer(collection(studentDb("u1"), "events/open/attendees")));
});

test("L2 any student who can read the event reads its attendeeCount", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  const event = await assertSucceeds(getDoc(doc(studentDb("u1"), "events/open")));
  assert.equal(event.data().attendeeCount, 3);
});

// The rows that need a decimal number (C5, O10, R24), through the emulator's REST API with the
// unsigned token the emulator accepts: the JS SDK always sends a whole number as an integer.

const REST_ROOT = `projects/${PROJECT_ID}/databases/(default)/documents`;

/** An unsigned ID token for the verified EPFL student [uid], which only the emulator accepts. */
function emulatorToken(uid) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${PROJECT_ID}`, aud: PROJECT_ID, sub: uid, user_id: uid,
    email: "student@epfl.ch", email_verified: true, iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: "password", identities: {} },
  })}.`;
}

/** Commits [writes] (REST writes) as the student [uid]; true if the rules allowed them. */
async function restCommitted(uid, writes) {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  if (!host) throw new Error("FIRESTORE_EMULATOR_HOST isn't set: run inside emulators:exec");
  const response = await fetch(`http://${host}/v1/${REST_ROOT}:commit`, {
    method: "POST",
    headers: { Authorization: `Bearer ${emulatorToken(uid)}`, "Content-Type": "application/json" },
    body: JSON.stringify({ writes }),
  });
  await response.text();
  if (response.status !== 200 && response.status !== 403) {
    throw new Error(`Unexpected HTTP ${response.status} from the emulator`);
  }
  return response.status === 200;
}

/** A field of a stored document as the REST API returns it, with its type. */
async function restField(documentPath, field) {
  const response = await fetch(
    `http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/${REST_ROOT}/${documentPath}`,
    { headers: { Authorization: "Bearer owner" } },
  );
  return ((await response.json()).fields || {})[field];
}

const restInt = (value) => ({ integerValue: String(value) });
const restDouble = (value) => ({ doubleValue: value });

/** The REST fields of an upcoming event created by [uid], with [overrides] (REST values too). */
function restEventFields(uid, overrides = {}) {
  const string = (value) => ({ stringValue: value });
  return {
    title: string("Study session"), description: string(""), category: string("STUDY"),
    location: { geoPointValue: { latitude: 46.5191, longitude: 6.5668 } },
    startTime: { timestampValue: "2099-01-01T18:00:00Z" }, endTime: { nullValue: null },
    capacity: restInt(10), isPrivate: { booleanValue: false }, createdBy: string(uid),
    organizerIds: { arrayValue: { values: [string(uid)] } },
    allowedUids: { arrayValue: { values: [string(uid)] } },
    isAssociationEvent: { booleanValue: false }, ...overrides,
  };
}

const restSetEvent = (eventId, fields, precondition) => ({
  update: { name: `${REST_ROOT}/events/${eventId}`, fields }, currentDocument: precondition,
});
const restRegistration = (eventId, uid) => ({
  update: { name: `${REST_ROOT}/events/${eventId}/attendees/${uid}`, fields: { uid: { stringValue: uid } } },
  updateTransforms: [{ fieldPath: "registeredAt", setToServerValue: "REQUEST_TIME" }],
  currentDocument: { exists: false },
});
const restSetCount = (eventId, value) => ({
  update: { name: `${REST_ROOT}/events/${eventId}`, fields: { attendeeCount: value } },
  updateMask: { fieldPaths: ["attendeeCount"] },
  currentDocument: { exists: true },
});
const restIncrementCount = (eventId, value) => ({
  update: { name: `${REST_ROOT}/events/${eventId}`, fields: {} },
  updateMask: { fieldPaths: [] },
  updateTransforms: [{ fieldPath: "attendeeCount", increment: value }],
  currentDocument: { exists: true },
});

test("C5 an event created with attendeeCount 0.0 is denied; with the integer 0, allowed", async () => {
  const create = (id, count) => restCommitted("u1", [
    restSetEvent(id, restEventFields("u1", { attendeeCount: count }), { exists: false }),
  ]);
  assert.equal(await create("decimal", restDouble(0.0)), false);
  assert.equal(await create("whole", restInt(0)), true);
  assert.deepEqual(await restField("events/whole", "attendeeCount"), restInt(0));
});

test("O10 an organizer overwriting the event with attendeeCount 3.0 for 3 is denied; with 3, allowed", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  const overwrite = (count) => restCommitted("u2", [restSetEvent(
    "open",
    restEventFields("u2", { title: { stringValue: "Rewritten" }, attendeeCount: count }),
    { exists: true },
  )]);
  assert.equal(await overwrite(restDouble(3.0)), false);
  assert.deepEqual(await restField("events/open", "attendeeCount"), restInt(3));
  assert.equal(await overwrite(restInt(3)), true);
  assert.deepEqual(await restField("events/open", "attendeeCount"), restInt(3));
});

test("R24 a counted registration whose counter is a decimal (4.0, or increment(1.0)) is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  const register = (uid, change) => restCommitted(uid, [restRegistration("open", uid), change]);
  assert.equal(await register("u1", restSetCount("open", restDouble(4.0))), false);
  assert.equal(await register("u1", restIncrementCount("open", restDouble(1.0))), false);
  await expectStored("open", 3, ["u10", "u11", "u12"]);
  assert.deepEqual(await restField("events/open", "attendeeCount"), restInt(3));
  // The same commits with integers are allowed.
  assert.equal(await register("u1", restSetCount("open", restInt(4))), true);
  assert.equal(await register("u3", restIncrementCount("open", restInt(1))), true);
  assert.deepEqual(await restField("events/open", "attendeeCount"), restInt(5));
});

test("R24 a counted cancellation whose counter is a decimal (2.0, or increment(-1.0)) is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  const remove = { delete: `${REST_ROOT}/events/open/attendees/u10` };
  assert.equal(await restCommitted("u10", [remove, restSetCount("open", restDouble(2.0))]), false);
  assert.equal(
    await restCommitted("u10", [remove, restIncrementCount("open", restDouble(-1.0))]), false,
  );
  assert.deepEqual(await restField("events/open", "attendeeCount"), restInt(3));
  assert.equal(await restCommitted("u10", [remove, restIncrementCount("open", restInt(-1))]), true);
  assert.deepEqual(await restField("events/open", "attendeeCount"), restInt(2));
});

// Numbers compare by value, so a counted registration may send the stored capacity back as 10.0.
// The rules take any number as the capacity and the app reads it as a Number, so nothing depends
// on its type; a different value is still a change, and denied.
test("a counted registration may send the stored capacity back as 10.0, not another value", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  const resend = restSetEvent(
    "open", restEventFields("u2", { capacity: restDouble(10.0), attendeeCount: restInt(4) }),
    { exists: true },
  );
  assert.equal(await restCommitted("u1", [restRegistration("open", "u1"), resend]), true);
  assert.deepEqual(await restField("events/open", "capacity"), restDouble(10));
  const raise = restSetEvent(
    "open", restEventFields("u2", { capacity: restDouble(10.5), attendeeCount: restInt(5) }),
    { exists: true },
  );
  assert.equal(await restCommitted("u3", [restRegistration("open", "u3"), raise]), false);
  assert.deepEqual(await restField("events/open", "attendeeCount"), restInt(4));
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

// ---- usernames/{name} (#448) ----
// usernames/{name} holds the uid of the student whose users/{uid}.username names it, copied to
// their public profile. Students get one name, never list them; a name and its profile are tied
// both ways, so no write keeps two names or a name the profile doesn't hold.

test("the rules refuse exactly the reserved names and format the app checks", () => {
  const { reserved, pattern } = appUsernameRules();
  assert.deepEqual(rulesCodes("reservedUsernames"), reserved);
  const rules = readFileSync(path.join(__dirname, "firestore.rules"), "utf8");
  assert.ok(rules.includes(`name.matches('${pattern}')`), "the rules' username pattern differs");
});

/**
 * Seeds the student [uid] holding [name]: both profiles naming it and usernames/[name], with the
 * rules off.
 */
async function seedUsername(uid, name) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, `users/${uid}`), privateProfile(uid, { username: name }));
    await setDoc(doc(db, `publicProfiles/${uid}`), publicProfile({ username: name }));
    await setDoc(doc(db, `usernames/${name}`), { uid });
  });
}

/**
 * [uid] takes [name] as the app does, in one batch: both profiles name it, usernames/[name] is
 * created and, when [previous] is set, usernames/[previous] is deleted. [skip] leaves out the
 * write it names ("users", "public", "create" or "delete"), and [data] replaces the new document.
 */
function claim(db, uid, name, { previous, skip, data = { uid } } = {}) {
  const batch = writeBatch(db);
  if (skip !== "users") batch.update(doc(db, `users/${uid}`), { username: name });
  if (skip !== "public") batch.update(doc(db, `publicProfiles/${uid}`), { username: name });
  if (skip !== "create") batch.set(doc(db, `usernames/${name}`), data);
  if (previous && skip !== "delete") batch.delete(doc(db, `usernames/${previous}`));
  return batch.commit();
}

async function usernameHolder(name) {
  let holder;
  await env.withSecurityRulesDisabled(async (context) => {
    const snapshot = await getDoc(doc(context.firestore(), `usernames/${name}`));
    holder = snapshot.exists() ? snapshot.data().uid : null;
  });
  return holder;
}

// -- get, never list --

test("a student can get a username another student holds, and one nobody holds", async () => {
  await seedUsername("u2", "lea");
  const snapshot = await assertSucceeds(getDoc(doc(studentDb("u1"), "usernames/lea")));
  assert.deepEqual(snapshot.data(), { uid: "u2" });
  await assertSucceeds(getDoc(doc(studentDb("u1"), "usernames/nobody")));
});

for (const [name, listing] of [
  ["the whole collection", (names) => names],
  ["a page of it", (names) => query(names, limit(1))],
  ["the names one student holds", (names) => query(names, where("uid", "==", "u2"))],
]) {
  test(`a student can't list usernames: ${name}`, async () => {
    await seedUsername("u2", "lea");
    await assertFails(getDocs(listing(collection(studentDb("u1"), "usernames"))));
  });
}

for (const [name, db] of [
  ["a pending association", () => associationDb("club")],
  ["a verified association", () => associationDb("verifiedClub")],
  ["an @epfl.ch association", () => epflAssociationDb("verifiedClub")],
  ["an unverified student", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} can't get a username`, async () => {
    await seedUsername("u2", "lea");
    await assertFails(getDoc(doc(db(), "usernames/lea")));
  });
}

test("a deleted account can't get a username", async () => {
  await seedUsername("u2", "lea");
  await seedDeletedAccount("u1");
  await assertFails(getDoc(doc(studentDb("u1"), "usernames/lea")));
});

// -- claim --

test("a student claims a free name with both profiles in one batch", async () => {
  await seedStudentWithPublicProfile("u1");
  await assertSucceeds(claim(studentDb("u1"), "u1", "franek"));
  assert.equal(await usernameHolder("franek"), "u1");
});

test("a student claims a free name in a transaction", async () => {
  await seedStudentWithPublicProfile("u1");
  const db = studentDb("u1");
  await assertSucceeds(
    runTransaction(db, async (transaction) => {
      const name = doc(db, "usernames/franek");
      if ((await transaction.get(name)).exists()) throw new Error("Expected a free name");
      transaction.update(doc(db, "users/u1"), { username: "franek" });
      transaction.update(doc(db, "publicProfiles/u1"), { username: "franek" });
      transaction.set(name, { uid: "u1" });
    }),
  );
});

test("a student without a public profile (saved before #229) claims a name too", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "users/u1"), privateProfile("u1"));
  });
  await assertSucceeds(claim(studentDb("u1"), "u1", "franek", { skip: "public" }));
});

for (const skip of ["users", "create", "public"]) {
  test(`a claim without its ${skip} write is denied`, async () => {
    await seedStudentWithPublicProfile("u1");
    await assertFails(claim(studentDb("u1"), "u1", "franek", { skip }));
  });
}

test("a public profile naming another username than the private one is denied", async () => {
  await seedUsername("u1", "franek");
  await assertFails(updateDoc(doc(studentDb("u1"), "publicProfiles/u1"), { username: "other" }));
});

for (const [name, data] of [
  ["for another student", { uid: "u2" }],
  ["with another field", { uid: "u1", createdAt: serverTimestamp() }],
  ["without its uid", {}],
]) {
  test(`a claim ${name} is denied`, async () => {
    await seedStudentWithPublicProfile("u1");
    await assertFails(claim(studentDb("u1"), "u1", "franek", { data }));
  });
}

for (const name of ["Franek", "9lives", "_franek", "ab", "a".repeat(21), "fra-nek", "fra nek"]) {
  test(`a name the format refuses (${name}) can't be claimed`, async () => {
    await seedStudentWithPublicProfile("u1");
    await assertFails(claim(studentDb("u1"), "u1", name));
  });
}

test("names at the format's limits can be claimed", async () => {
  await seedStudentWithPublicProfile("u1");
  await seedStudentWithPublicProfile("u2");
  await assertSucceeds(claim(studentDb("u1"), "u1", "a.1"));
  await assertSucceeds(claim(studentDb("u2"), "u2", `a${"_".repeat(19)}`));
});

for (const name of appUsernameRules().reserved) {
  test(`the reserved name ${name} can't be claimed`, async () => {
    await seedStudentWithPublicProfile("u1");
    await assertFails(claim(studentDb("u1"), "u1", name));
  });
}

test("a name another student holds can't be taken", async () => {
  await seedUsername("u2", "lea");
  await seedStudentWithPublicProfile("u1");
  await assertFails(claim(studentDb("u1"), "u1", "lea"));
  await assertFails(claim(studentDb("u1"), "u1", "lea", { skip: "create" }));
  assert.equal(await usernameHolder("lea"), "u2");
});

// u9 has no seeded users/{uid}, so these writes are creates.
test("a profile can't be created with a username, even holding it", async () => {
  const db = studentDb("u9");
  const withName = writeBatch(db);
  withName.set(doc(db, "users/u9"), privateProfile("u9", { username: "franek" }));
  withName.set(doc(db, "publicProfiles/u9"), publicProfile({ username: "franek" }));
  withName.set(doc(db, "usernames/franek"), { uid: "u9" });
  await assertFails(withName.commit());

  const withoutName = writeBatch(db);
  withoutName.set(doc(db, "users/u9"), privateProfile("u9"));
  withoutName.set(doc(db, "publicProfiles/u9"), publicProfile());
  await assertSucceeds(withoutName.commit());
});

test("a profile naming a name nobody holds can only claim it as its own", async () => {
  // Not a state the app writes; an admin's edit could leave one.
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "users/u1"), privateProfile("u1", { username: "ghost" }));
  });
  await assertFails(setDoc(doc(studentDb("u1"), "usernames/ghost"), { uid: "u2" }));
  await assertSucceeds(setDoc(doc(studentDb("u1"), "usernames/ghost"), { uid: "u1" }));
});

for (const [name, uid] of [
  ["a pending association", "club"],
  ["a verified association", "verifiedClub"],
]) {
  test(`${name} can't claim a username`, async () => {
    const db = associationDb(uid);
    const batch = writeBatch(db);
    batch.update(doc(db, `users/${uid}`), { username: "club" });
    batch.set(doc(db, "usernames/club"), { uid });
    await assertFails(batch.commit());
  });
}

test("a deleted account can't claim a username, even with a profile left", async () => {
  // No public profile, so only the usernames/{name} rule can refuse the claim (#517 review).
  await seedUser("u1", privateProfile("u1"));
  await seedDeletedAccount("u1");
  await assertFails(claim(studentDb("u1"), "u1", "franek", { skip: "public" }));
});

// -- rename and removal --

test("a student renames, freeing the previous name in the same write", async () => {
  await seedUsername("u1", "franek");
  await assertSucceeds(claim(studentDb("u1"), "u1", "franek2", { previous: "franek" }));
  assert.equal(await usernameHolder("franek"), null);
  assert.equal(await usernameHolder("franek2"), "u1");
});

test("claiming @franek2 while keeping @franek is denied", async () => {
  await seedUsername("u1", "franek");
  await assertFails(claim(studentDb("u1"), "u1", "franek2", { previous: "franek", skip: "delete" }));
  assert.equal(await usernameHolder("franek2"), null);
});

test("a second name can't be claimed while the profile keeps the first", async () => {
  await seedUsername("u1", "franek");
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "usernames/franek2"), { uid: "u1" }));
});

test("a student removes their username, freeing it in the same write", async () => {
  await seedUsername("u1", "franek");
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u1"), { username: deleteField() });
  batch.update(doc(db, "publicProfiles/u1"), { username: deleteField() });
  batch.delete(doc(db, "usernames/franek"));
  await assertSucceeds(batch.commit());
  assert.equal(await usernameHolder("franek"), null);
});

test("removing the username from the profile while keeping the name is denied", async () => {
  await seedUsername("u1", "franek");
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.update(doc(db, "users/u1"), { username: deleteField() });
  batch.update(doc(db, "publicProfiles/u1"), { username: deleteField() });
  await assertFails(batch.commit());
});

test("freeing the name the profile still names is denied", async () => {
  await seedUsername("u1", "franek");
  await assertFails(deleteDoc(doc(studentDb("u1"), "usernames/franek")));
});

test("another student can't free or overwrite someone's name", async () => {
  await seedUsername("u2", "lea");
  await seedStudentWithPublicProfile("u1");
  await assertFails(deleteDoc(doc(studentDb("u1"), "usernames/lea")));
  await assertFails(setDoc(doc(studentDb("u1"), "usernames/lea"), { uid: "u1" }));
});

test("an association can't free a name, even one held under its own uid", async () => {
  // Not a state the app writes: only students hold names, so only a student frees one.
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "usernames/club"), { uid: "club" });
  });
  await assertFails(deleteDoc(doc(associationDb("club"), "usernames/club")));
});

test("nobody updates a username document, not even its holder", async () => {
  await seedUsername("u1", "franek");
  await assertFails(setDoc(doc(studentDb("u1"), "usernames/franek"), { uid: "u1" }));
});

test("an edit that keeps the username needs the public copy to keep it too", async () => {
  await seedUsername("u1", "franek");
  const db = studentDb("u1");
  const keeps = writeBatch(db);
  keeps.update(doc(db, "users/u1"), { displayName: "New Name" });
  keeps.set(doc(db, "publicProfiles/u1"), publicProfile({ displayName: "New Name", username: "franek" }));
  await assertSucceeds(keeps.commit());

  const drops = writeBatch(db);
  drops.update(doc(db, "users/u1"), { displayName: "Newer Name" });
  drops.set(doc(db, "publicProfiles/u1"), publicProfile({ displayName: "Newer Name" }));
  await assertFails(drops.commit());
});

// -- the profile deleted --

/** Seeds [uid] holding [name] without a public profile, as a profile saved before #229. */
async function seedUsernameWithoutPublicProfile(uid, name) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, `users/${uid}`), privateProfile(uid, { username: name }));
    await setDoc(doc(db, `usernames/${name}`), { uid });
  });
}

/** Deletes users/[uid] and, unless [keepName], usernames/[name], in one batch as [uid]. */
function deleteProfile(uid, name, { keepName = false } = {}) {
  const db = studentDb(uid);
  const batch = writeBatch(db);
  batch.delete(doc(db, `users/${uid}`));
  if (!keepName) batch.delete(doc(db, `usernames/${name}`));
  return batch.commit();
}

test("a profile without a public copy is deleted with its name in the same write", async () => {
  await seedUsernameWithoutPublicProfile("u1", "franek");
  await assertSucceeds(deleteProfile("u1", "franek"));
  assert.equal(await usernameHolder("franek"), null);
});

test("deleting the profile while keeping the name is denied", async () => {
  // No public copy, so only the name's tie can refuse it.
  await seedUsernameWithoutPublicProfile("u1", "franek");
  await assertFails(deleteProfile("u1", "franek", { keepName: true }));
  assert.equal(await usernameHolder("franek"), "u1");
});

// Its public copy keeps the name, which no client can delete: freeing the name would let someone
// else claim it and show it on a second public profile (#517 review).
test("a profile with a username can't be deleted while its public copy exists", async () => {
  await seedUsername("u1", "franek");
  await assertFails(deleteProfile("u1", "franek"));
  assert.equal(await usernameHolder("franek"), "u1");
});

test("a profile without a username is still deleted on its own", async () => {
  await seedStudentWithPublicProfile("u1");
  await assertSucceeds(deleteDoc(doc(studentDb("u1"), "users/u1")));
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

test("an active student can create and join groups; a deleted token cannot recreate membership", async () => {
  const db = studentDb("u1");
  await assertSucceeds(createGroupAs(db, "u1", "e1", "own"));
  await assertSucceeds(leaveAs(db, "u1", "e1", "own"));
  await seedGroup("e1", "other", ["u2"]);
  await assertSucceeds(joinAs(db, "u1", "e1", "other"));
  await assertSucceeds(leaveAs(db, "u1", "e1", "other"));
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "deletedAccounts/u1"), {});
    await deleteDoc(doc(context.firestore(), "users/u1"));
  });
  await assertFails(createGroupAs(db, "u1", "e1", "recreated"));
  await assertFails(joinAs(db, "u1", "e1", "other"));
  await expectMembers("e1", "other", ["u2"]);
});

test("only an active member can recreate their own marker for a surviving group", async () => {
  await seedGroup("e1", "g1", ["u1", "u2"]);
  const marker = doc(studentDb("u1"), "events/e1/groupMembers/u1");
  const removeMarker = () => env.withSecurityRulesDisabled(async (context) => {
    await deleteDoc(doc(context.firestore(), "events/e1/groupMembers/u1"));
  });
  await removeMarker();
  await assertSucceeds(setDoc(marker, { groupId: "g1" }));
  await removeMarker();
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "deletedAccounts/u1"), {});
  });
  await assertFails(setDoc(marker, { groupId: "g1" }));
});

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

// The Chats tab (#363) lists a student's groups with the query two tests above, memberUids
// array-contains their own UID. Any other query across events could return a group they aren't
// in, so it is denied as a whole, even when every group stored today is theirs.
for (const [name, listing] of [
  ["a page of every group", (groups) => query(groups, limit(1))],
  ["the groups of either of two students", (groups) =>
    query(groups, where("memberUids", "array-contains-any", ["u1", "u2"]))],
  ["the groups they started", (groups) => query(groups, where("createdBy", "==", "u1"))],
]) {
  test(`a student can't list groups across events by anything but their own membership: ${name}`, async () => {
    await seedGroup("e1", "mine", ["u1"]);
    await assertFails(getDocs(listing(collectionGroup(studentDb("u1"), "groups"))));
  });
}

// The cross-event rule matches a `groups` collection anywhere, but grants only that query. Only
// an admin could store a `groups` document outside an event; the own-groups query would then
// return it, which is why FirestoreGroupRepository.observeMyGroups skips such a document.
test("outside an event, a groups document is only listed by its member's own query, never fetched or written", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "clubs/c1/groups/stray"), {
      capacity: 4,
      memberUids: ["u1"],
      createdBy: "u1",
      createdAt: Timestamp.fromMillis(1_790_000_000_000),
    });
  });
  const db = studentDb("u1");
  const mine = await assertSucceeds(getDocs(query(
    collectionGroup(db, "groups"), where("memberUids", "array-contains", "u1"),
  )));
  expectIds(mine.docs.map((group) => group.ref.path), ["clubs/c1/groups/stray"]);
  await assertFails(getDoc(doc(db, "clubs/c1/groups/stray")));
  await assertFails(getDocs(collection(db, "clubs/c1/groups")));
  await assertFails(setDoc(doc(db, "clubs/c1/groups/new"), newGroup("u1")));
  await assertFails(updateDoc(doc(db, "clubs/c1/groups/stray"), { memberUids: ["u1", "u2"] }));
  await assertFails(deleteDoc(doc(db, "clubs/c1/groups/stray")));
});

for (const [name, uid, db] of [
  ["a verified association", "verifiedClub", () => associationDb("verifiedClub")],
  ["an unverified association", "club", () => associationDb("club")],
  ["an @epfl.ch association", "epflClub", () => epflAssociationDb("epflClub")],
  ["an unverified @epfl.ch user", "u1", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["an @epfl.ch user without an email_verified claim", "u1",
    () => env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore()],
  ["a verified non-EPFL user who isn't an association", "u1",
    () => firestoreAs("student@gmail.com", true, "u1")],
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

/**
 * The messages both kinds of chat refuse (#237, #428), each as its name and its changes to
 * newMessage; a change to undefined removes the field (see messageWith).
 */
const REFUSED_MESSAGE_CHANGES = [
  ["an empty text", { text: "" }],
  ["a text of 1001 characters", { text: "a".repeat(1001) }],
  ["501 emoji (1002 UTF-16 units)", { text: EMOJI.repeat(501) }],
  ["a text that isn't a string", { text: 42 }],
  // A list or a map has a size() too, so only `is string` refuses these.
  ["a text that is a list", { text: ["See you at 6?"] }],
  ["a text that is a map", { text: { text: "See you at 6?" } }],
  ["a null text", { text: null }],
  ["no text", { text: undefined }],
  ["no sender", { senderId: undefined }],
  ["a createdAt the client chose", { createdAt: Timestamp.fromMillis(1_790_000_000_000) }],
  // Later than the server's time: refused too, not only an earlier one.
  ["a createdAt in the future", { createdAt: Timestamp.fromDate(new Date("2099-01-01T00:00:00Z")) }],
  ["no createdAt", { createdAt: undefined }],
  ["another field", { editedAt: serverTimestamp() }],
];

/** newMessage([uid]) with [changes]; a change to undefined removes that field. */
function messageWith(uid, changes) {
  const message = { ...newMessage(uid), ...changes };
  for (const key of Object.keys(message)) {
    if (message[key] === undefined) delete message[key];
  }
  return message;
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

/**
 * The query FirestoreMessageRepository.observeMessages listens to, on the messages collection at
 * [path]: a group's (#237) or a one-to-one chat's (#428).
 */
function historyAt(db, path) {
  return query(collection(db, path), orderBy("createdAt"));
}

/** A group's chat, as observeMessages reads it. */
function chatOf(db, eventId, groupId) {
  return historyAt(db, `events/${eventId}/groups/${groupId}/messages`);
}

/** Stores a message from [senderId] at [millis] as the document [path], rules off. */
async function seedMessageAt(path, senderId, millis = 1_790_000_000_000) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), path), {
      senderId, text: "Seeded", createdAt: Timestamp.fromMillis(millis),
    });
  });
}

/** Stores the group message [id] from [senderId] at [millis], rules off. */
async function seedMessage(eventId, groupId, id, senderId, millis) {
  await seedMessageAt(messagePath(eventId, groupId, id), senderId, millis);
}

/** The data stored at the document [path], or null without it, rules off. */
async function storedAt(path) {
  let data;
  await env.withSecurityRulesDisabled(async (context) => {
    const snapshot = await getDoc(doc(context.firestore(), path));
    data = snapshot.exists() ? snapshot.data() : null;
  });
  return data;
}

/** The stored group message [id], or null without it, rules off. */
async function storedMessage(eventId, groupId, id) {
  return storedAt(messagePath(eventId, groupId, id));
}

/** Fails if the document [path] is stored. */
async function expectMissing(path) {
  const data = await storedAt(path);
  if (data !== null) throw new Error(`Expected no ${path}, got ${JSON.stringify(data)}`);
}

/** Fails if the group message [id] was stored. */
async function expectNoMessage(eventId, groupId, id = "new") {
  await expectMissing(messagePath(eventId, groupId, id));
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

/**
 * The query FirestoreMessageRepository.observeLatestMessage listens to, for the chat list (#363),
 * on the messages collection at [path]: a group's or a one-to-one chat's (#428).
 */
function latestAt(db, path) {
  return query(collection(db, path), orderBy("createdAt", "desc"), limit(1));
}

/** A group's latest message, as observeLatestMessage reads it. */
function latestMessageOf(db, eventId, groupId) {
  return latestAt(db, `events/${eventId}/groups/${groupId}/messages`);
}

test("a member reads only their group's latest message, as the chat list does", async () => {
  await seedGroup("e1", "g1", ["u2", "u1"]);
  await seedMessage("e1", "g1", "later", "u2", 1_790_000_060_000);
  await seedMessage("e1", "g1", "earlier", "u1", 1_790_000_000_000);
  const latest = await assertSucceeds(getDocs(latestMessageOf(studentDb("u1"), "e1", "g1")));
  expectIds(latest.docs.map((message) => message.id), ["later"]);
});

test("the latest message is denied to whoever can't read the chat", async () => {
  await seedGroup("e1", "g1", ["u2", "u4"]);
  await seedGroup("clubPublic", "g1", ["verifiedClub", "u2"]);
  await seedMessage("e1", "g1", "m1", "u2");
  await seedMessage("clubPublic", "g1", "m1", "u2");
  // A student who isn't in the group, an association listed in one, and a deleted account whose
  // membership hasn't been cleaned up yet.
  await assertFails(getDocs(latestMessageOf(studentDb("u1"), "e1", "g1")));
  await assertFails(getDocs(latestMessageOf(associationDb("verifiedClub"), "clubPublic", "g1")));
  await assertSucceeds(getDocs(latestMessageOf(studentDb("u4"), "e1", "g1")));
  await seedDeletedAccount("u4");
  await assertFails(getDocs(latestMessageOf(studentDb("u4"), "e1", "g1")));
});

// The accounts that can't read a chat can't read its latest message either, even listed in the
// group. Each time another member reads it, so the denial comes from the account alone.
for (const [name, uid, db, seed] of [
  ["an unverified association", "club", () => associationDb("club"), async () => {}],
  ["a verified @epfl.ch association", "epflClub", () => epflAssociationDb("epflClub"),
    () => seedEpflAssociation("epflClub", true)],
  ["an unverified @epfl.ch student", "u1", () => firestoreAs("student@epfl.ch", false, "u1"),
    async () => {}],
  ["a student without an email_verified claim", "u1",
    () => env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore(),
    async () => {}],
  ["a verified non-EPFL user who isn't an association", "u1",
    () => firestoreAs("student@gmail.com", true, "u1"), async () => {}],
  ["a signed-out user", "u1", () => env.unauthenticatedContext().firestore(), async () => {}],
]) {
  test(`${name} listed in a group can't read its latest message`, async () => {
    await seed();
    await seedGroup("e1", "g1", [uid, "u2"]);
    await seedMessage("e1", "g1", "m1", "u2");
    await assertFails(getDocs(latestMessageOf(db(), "e1", "g1")));
    await assertSucceeds(getDocs(latestMessageOf(studentDb("u2"), "e1", "g1")));
  });
}

test("a member reads the latest message without the event, and no longer once they left", async () => {
  // A private event the member was removed from, and an event that was deleted.
  await seedGroup("private", "g1", ["u2", "u3"]);
  await seedGroup("gone", "old", ["u3"]);
  await seedMessage("private", "g1", "m1", "u2");
  await seedMessage("gone", "old", "m2", "u3");
  await removeU3FromPrivateEvent();
  const db = studentDb("u3");
  const kept = await assertSucceeds(getDocs(latestMessageOf(db, "private", "g1")));
  expectIds(kept.docs.map((message) => message.id), ["m1"]);
  const orphaned = await assertSucceeds(getDocs(latestMessageOf(db, "gone", "old")));
  expectIds(orphaned.docs.map((message) => message.id), ["m2"]);

  await assertSucceeds(leaveAs(db, "u3", "private", "g1"));
  await assertFails(getDocs(latestMessageOf(db, "private", "g1")));
  // The last member leaving deletes the group; its messages stay, unreadable.
  await assertSucceeds(leaveAs(db, "u3", "gone", "old"));
  await assertFails(getDocs(latestMessageOf(db, "gone", "old")));
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

for (const [name, changes] of REFUSED_MESSAGE_CHANGES) {
  test(`a message with ${name} is denied`, async () => {
    await seedGroup("e1", "g1", ["u1"]);
    await assertFails(sendAs(studentDb("u1"), "e1", "g1", messageWith("u1", changes)));
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


test("deleted tokens lose chat access before membership cleanup; active members keep it", async () => {
  await seedGroup("e1", "g1", ["u1", "u2"]);
  await seedMessage("e1", "g1", "m1", "u1");
  await assertSucceeds(getDocs(chatOf(studentDb("u1"), "e1", "g1")));
  await seedDeletedAccount("u1");
  await assertFails(getDoc(doc(studentDb("u1"), messagePath("e1", "g1", "m1"))));
  await assertFails(getDocs(chatOf(studentDb("u1"), "e1", "g1")));
  await assertFails(sendAs(studentDb("u1"), "e1", "g1", newMessage("u1")));
  await assertSucceeds(getDocs(chatOf(studentDb("u2"), "e1", "g1")));
  await assertSucceeds(sendAs(studentDb("u2"), "e1", "g1", newMessage("u2")));
});

test("members read retained messages but cannot forge or reverse a deleted author", async () => {
  await seedGroup("e1", "g1", ["u1", "u2"]);
  await seedMessage("e1", "g1", "anonymous", "");
  const db = studentDb("u1");
  const ref = doc(db, messagePath("e1", "g1", "anonymous"));
  await assertSucceeds(getDoc(ref));
  await assertSucceeds(getDocs(chatOf(db, "e1", "g1")));
  await assertFails(sendAs(db, "e1", "g1", newMessage("")));
  await assertFails(updateDoc(ref, {senderId: "u1"}));
  await assertFails(updateDoc(ref, {text: "Changed"}));
  await assertFails(deleteDoc(ref));
});

// ---- friendships/{pairId} (#357, designed in #338) ----

/** A new friend request [from] sends to [to], as FirestoreFriendRepository writes it. */
function friendRequest(from, to, changes = {}) {
  const data = {
    uids: [from, to].sort(),
    requestedBy: from,
    status: "pending",
    createdAt: serverTimestamp(),
    ...changes,
  };
  for (const key of Object.keys(data)) {
    if (data[key] === undefined) delete data[key];
  }
  return data;
}

function friendshipRef(db, id) {
  return doc(db, `friendships/${id}`);
}

/** Seeds students u1, u2 and u3 with public profiles, so each can be sent a request. */
async function seedFriendStudents() {
  for (const uid of ["u1", "u2", "u3"]) await seedStudentWithPublicProfile(uid);
}

/** Stores friendships/[id] with the rules off, as a request already sent. */
async function seedFriendship(id, data) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `friendships/${id}`), data);
  });
}

/** A request u1 sent to u2 at a fixed time, stored with the rules off. */
async function seedPendingRequest(changes = {}) {
  await seedFriendship("u1~u2", {
    uids: ["u1", "u2"],
    requestedBy: "u1",
    status: "pending",
    createdAt: Timestamp.fromDate(new Date("2026-11-16T10:00:00Z")),
    ...changes,
  });
}

/** What u2 writes to accept u1's request. */
function acceptance(changes = {}) {
  return { status: "accepted", acceptedAt: serverTimestamp(), ...changes };
}

async function storedFriendship(id) {
  let data = null;
  await env.withSecurityRulesDisabled(async (context) => {
    const snapshot = await getDoc(doc(context.firestore(), `friendships/${id}`));
    data = snapshot.exists() ? snapshot.data() : null;
  });
  return data;
}

// Create

test("a student can send a friend request to a student with a public profile", async () => {
  await seedFriendStudents();

  await assertSucceeds(setDoc(friendshipRef(studentDb("u1"), "u1~u2"), friendRequest("u1", "u2")));
  await assertSucceeds(setDoc(friendshipRef(studentDb("u3"), "u2~u3"), friendRequest("u3", "u2")));
});

test("a request is sent as the app does: in a transaction that reads the missing pair first", async () => {
  await seedFriendStudents();
  const db = studentDb("u1");

  await assertSucceeds(
    runTransaction(db, async (transaction) => {
      const existing = await transaction.get(friendshipRef(db, "u1~u2"));
      if (existing.exists()) throw new Error("The pair's document should be missing");
      transaction.set(friendshipRef(db, "u1~u2"), friendRequest("u1", "u2"));
    }),
  );
});

test("a second request between the same two students is denied, in either direction", async () => {
  await seedFriendStudents();
  await setDoc(friendshipRef(studentDb("u1"), "u1~u2"), friendRequest("u1", "u2"));

  await assertFails(setDoc(friendshipRef(studentDb("u2"), "u1~u2"), friendRequest("u2", "u1")));
  await assertFails(setDoc(friendshipRef(studentDb("u1"), "u1~u2"), friendRequest("u1", "u2")));
  if ((await storedFriendship("u1~u2")).requestedBy !== "u1") throw new Error("The request changed");
});

test("a request must be stored under the pair's exact ID", async () => {
  await seedFriendStudents();
  const db = studentDb("u1");

  for (const id of ["u2~u1", "u1~u2~", "u1~u2~u3", "u1~u3", "u1", "~u1~u2", "u1~~u2"]) {
    await assertFails(setDoc(friendshipRef(db, id), friendRequest("u1", "u2"))).catch((error) => {
      throw new Error(`The ID ${id} was allowed: ${error.message}`);
    });
  }
});

test("a request needs two different UIDs, sorted, one of them the sender's", async () => {
  await seedFriendStudents();
  const db = studentDb("u1");

  await assertFails(setDoc(friendshipRef(db, "u1~u2"), friendRequest("u1", "u2", { uids: ["u2", "u1"] })));
  await assertFails(setDoc(friendshipRef(db, "u1~u1"), friendRequest("u1", "u1", { uids: ["u1", "u1"] })));
  await assertFails(setDoc(friendshipRef(db, "u2~u3"), friendRequest("u2", "u3")));
  // Sent "as yourself" for a pair you aren't in.
  await assertFails(setDoc(friendshipRef(db, "u2~u3"), friendRequest("u2", "u3", { requestedBy: "u1" })));
  await assertFails(
    setDoc(friendshipRef(db, "u1~u2"), friendRequest("u1", "u2", { uids: ["u1", "u2", "u3"] })),
  );
  await assertFails(setDoc(friendshipRef(db, "u1~u2"), friendRequest("u1", "u2", { uids: "u1~u2" })));
});

test("a request is sent only as yourself, pending, at the server's time", async () => {
  await seedFriendStudents();
  const db = studentDb("u1");
  const ref = friendshipRef(db, "u1~u2");

  await assertFails(setDoc(ref, friendRequest("u1", "u2", { requestedBy: "u2" })));
  await assertFails(setDoc(ref, friendRequest("u1", "u2", { status: "accepted" })));
  await assertFails(
    setDoc(ref, friendRequest("u1", "u2", { createdAt: Timestamp.fromDate(new Date()) })),
  );
});

test("a request holds exactly its four fields", async () => {
  await seedFriendStudents();
  const ref = friendshipRef(studentDb("u1"), "u1~u2");

  await assertFails(setDoc(ref, friendRequest("u1", "u2", { acceptedAt: serverTimestamp() })));
  await assertFails(setDoc(ref, friendRequest("u1", "u2", { displayName: "Student One" })));
  for (const field of ["uids", "requestedBy", "status", "createdAt"]) {
    await assertFails(setDoc(ref, friendRequest("u1", "u2", { [field]: undefined }))).catch(
      (error) => {
        throw new Error(`A request without ${field} was allowed: ${error.message}`);
      },
    );
  }
});

test("a request to a UID without a public profile is denied", async () => {
  await seedFriendStudents();

  // u9 has no public profile: an association, a deleted account or a made-up UID.
  await assertFails(setDoc(friendshipRef(studentDb("u1"), "u1~u9"), friendRequest("u1", "u9")));
  // verifiedClub is an association, which never has one.
  await assertFails(
    setDoc(friendshipRef(studentDb("u1"), "u1~verifiedClub"), friendRequest("u1", "verifiedClub")),
  );
});

test("an association can't send a friend request", async () => {
  await seedFriendStudents();
  await storeUserAsAdmin("epflClub", { uid: "epflClub", accountType: "association" });

  await assertFails(
    setDoc(friendshipRef(associationDb("verifiedClub"), "u1~verifiedClub"), friendRequest("verifiedClub", "u1")),
  );
  await assertFails(
    setDoc(
      friendshipRef(firestoreAs("club@epfl.ch", true, "epflClub"), "epflClub~u1"),
      friendRequest("epflClub", "u1"),
    ),
  );
});

test("a request to an account that is now an association's is denied, even with a public profile left", async () => {
  await seedFriendStudents();
  // u4 still has a public profile, but its users/{uid} is now an association's (#416 review).
  await seedStudentWithPublicProfile("u4");
  await storeUserAsAdmin("u4", { uid: "u4", accountType: "association" });

  await assertFails(setDoc(friendshipRef(studentDb("u1"), "u1~u4"), friendRequest("u1", "u4")));
  await assertSucceeds(setDoc(friendshipRef(studentDb("u1"), "u1~u2"), friendRequest("u1", "u2")));
});

test("an EPFL account can't send a request in the batch that makes it an association", async () => {
  await seedFriendStudents();
  // A new @epfl.ch account without a profile counts as a student until its profile exists.
  const db = firestoreAs("club@epfl.ch", true, "aNewClub");
  const batch = writeBatch(db);
  batch.set(doc(db, "users/aNewClub"), { uid: "aNewClub", accountType: "association" });
  batch.set(friendshipRef(db, "aNewClub~u1"), friendRequest("aNewClub", "u1"));

  await assertFails(batch.commit());
  if ((await storedFriendship("aNewClub~u1")) !== null) throw new Error("The request was stored");
  // Each write alone is allowed: the association profile, and a request before any profile.
  await assertSucceeds(setDoc(doc(db, "users/aNewClub"), { uid: "aNewClub", accountType: "association" }));
  const other = firestoreAs("other@epfl.ch", true, "aNewStudent");
  await assertSucceeds(setDoc(friendshipRef(other, "aNewStudent~u1"), friendRequest("aNewStudent", "u1")));
});

test("an unverified or signed-out account can't send a friend request", async () => {
  await seedFriendStudents();

  await assertFails(
    setDoc(friendshipRef(firestoreAs("student@epfl.ch", false, "u1"), "u1~u2"), friendRequest("u1", "u2")),
  );
  await assertFails(
    setDoc(friendshipRef(env.unauthenticatedContext().firestore(), "u1~u2"), friendRequest("u1", "u2")),
  );
});

test("a deleted account can't send a friend request", async () => {
  await seedFriendStudents();
  await seedDeletedAccount("u1");

  await assertFails(setDoc(friendshipRef(studentDb("u1"), "u1~u2"), friendRequest("u1", "u2")));
});

// Read

test("both students of the pair can read their friendship, nobody else can", async () => {
  await seedFriendStudents();
  await seedPendingRequest();

  await assertSucceeds(getDoc(friendshipRef(studentDb("u1"), "u1~u2")));
  await assertSucceeds(getDoc(friendshipRef(studentDb("u2"), "u1~u2")));
  await assertFails(getDoc(friendshipRef(studentDb("u3"), "u1~u2")));
  await assertFails(getDoc(friendshipRef(associationDb("verifiedClub"), "u1~u2")));
  await assertFails(getDoc(friendshipRef(env.unauthenticatedContext().firestore(), "u1~u2")));
});

test("a student can read a missing friendship only under their own pair ID", async () => {
  await seedFriendStudents();

  await assertSucceeds(getDoc(friendshipRef(studentDb("u1"), "u1~u2")));
  await assertSucceeds(getDoc(friendshipRef(studentDb("u2"), "u1~u2")));
  // A third student can't tell a missing friendship from one they may not read.
  await assertFails(getDoc(friendshipRef(studentDb("u3"), "u1~u2")));
  for (const id of ["u2~u1", "u1~u2~", "u1", "u1~u2~u3", "~u1", "u1~", "u1~u1"]) {
    await assertFails(getDoc(friendshipRef(studentDb("u1"), id))).catch((error) => {
      throw new Error(`Reading the missing ${id} was allowed: ${error.message}`);
    });
  }
  await assertFails(getDoc(friendshipRef(associationDb("verifiedClub"), "u1~verifiedClub")));
});

test("a student lists friendships only with uids array-contains their own UID", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  await seedFriendship("u2~u3", { uids: ["u2", "u3"], requestedBy: "u3", status: "pending", createdAt: FAR_FUTURE });
  const db = studentDb("u1");

  const mine = await assertSucceeds(
    getDocs(query(collection(db, "friendships"), where("uids", "array-contains", "u1"))),
  );
  if (mine.docs.map((d) => d.id).join() !== "u1~u2") throw new Error(`Listed ${mine.docs.map((d) => d.id)}`);
  await assertFails(getDocs(collection(db, "friendships")));
  await assertFails(getDocs(query(collection(db, "friendships"), where("uids", "array-contains", "u3"))));
  await assertFails(getDocs(query(collection(db, "friendships"), where("status", "==", "pending"))));
  await assertFails(
    getDocs(
      query(collection(associationDb("verifiedClub"), "friendships"), where("uids", "array-contains", "verifiedClub")),
    ),
  );
});

test("a deleted account can't read or list its friendships", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  await seedDeletedAccount("u1");
  const db = studentDb("u1");

  await assertFails(getDoc(friendshipRef(db, "u1~u2")));
  await assertFails(getDoc(friendshipRef(db, "u1~u3")));
  await assertFails(getDocs(query(collection(db, "friendships"), where("uids", "array-contains", "u1"))));
  await assertSucceeds(getDoc(friendshipRef(studentDb("u2"), "u1~u2")));
});

// Update (accepting)

test("the recipient accepts a request, at the server's time", async () => {
  await seedFriendStudents();
  await seedPendingRequest();

  await assertSucceeds(updateDoc(friendshipRef(studentDb("u2"), "u1~u2"), acceptance()));
  const stored = await storedFriendship("u1~u2");
  if (stored.status !== "accepted" || stored.requestedBy !== "u1") {
    throw new Error(`Unexpected friendship: ${JSON.stringify(stored)}`);
  }
});

test("the recipient accepts as the app does: in a transaction that reads it again", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  const db = studentDb("u2");

  await assertSucceeds(
    runTransaction(db, async (transaction) => {
      const stored = await transaction.get(friendshipRef(db, "u1~u2"));
      if (stored.get("status") !== "pending") throw new Error("Expected a pending request");
      transaction.update(friendshipRef(db, "u1~u2"), acceptance());
    }),
  );
});

test("the sender can't accept their own request, nor can anyone else", async () => {
  await seedFriendStudents();
  await seedPendingRequest();

  await assertFails(updateDoc(friendshipRef(studentDb("u1"), "u1~u2"), acceptance()));
  await assertFails(updateDoc(friendshipRef(studentDb("u3"), "u1~u2"), acceptance()));
  await assertFails(updateDoc(friendshipRef(associationDb("verifiedClub"), "u1~u2"), acceptance()));
  if ((await storedFriendship("u1~u2")).status !== "pending") throw new Error("The request changed");
});

test("accepting changes only status to accepted and sets acceptedAt to the server's time", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  const ref = friendshipRef(studentDb("u2"), "u1~u2");

  await assertFails(updateDoc(ref, acceptance({ acceptedAt: Timestamp.fromDate(new Date()) })));
  await assertFails(updateDoc(ref, { status: "accepted" }));
  await assertFails(updateDoc(ref, acceptance({ status: "pending" })));
  await assertFails(updateDoc(ref, acceptance({ status: "blocked" })));
  await assertFails(updateDoc(ref, acceptance({ requestedBy: "u2" })));
  await assertFails(updateDoc(ref, acceptance({ uids: ["u2", "u3"] })));
  await assertFails(updateDoc(ref, acceptance({ createdAt: serverTimestamp() })));
  await assertFails(updateDoc(ref, acceptance({ note: "Hi" })));
});

test("an accepted friendship can't be updated again", async () => {
  await seedFriendStudents();
  await seedPendingRequest({ status: "accepted", acceptedAt: FAR_FUTURE });
  const ref = friendshipRef(studentDb("u2"), "u1~u2");

  await assertFails(updateDoc(ref, acceptance()));
  await assertFails(updateDoc(ref, { status: "pending", acceptedAt: deleteField() }));
  await assertFails(updateDoc(friendshipRef(studentDb("u1"), "u1~u2"), { status: "pending", acceptedAt: deleteField() }));
});

test("accepting is denied when either student's account was deleted", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  await seedDeletedAccount("u1");

  await assertFails(updateDoc(friendshipRef(studentDb("u2"), "u1~u2"), acceptance()));

  await env.clearFirestore();
  await seedFriendStudents();
  await seedPendingRequest();
  await seedDeletedAccount("u2");

  await assertFails(updateDoc(friendshipRef(studentDb("u2"), "u1~u2"), acceptance()));
});

// Delete (declining, cancelling, removing)

test("either student can delete their request or friendship", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  await assertSucceeds(deleteDoc(friendshipRef(studentDb("u2"), "u1~u2")));

  await seedPendingRequest();
  await assertSucceeds(deleteDoc(friendshipRef(studentDb("u1"), "u1~u2")));

  await seedPendingRequest({ status: "accepted", acceptedAt: FAR_FUTURE });
  await assertSucceeds(deleteDoc(friendshipRef(studentDb("u2"), "u1~u2")));
  if ((await storedFriendship("u1~u2")) !== null) throw new Error("The friendship is still there");
});

test("a student deletes as the app does: in a transaction that reads it again", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  const db = studentDb("u1");

  await assertSucceeds(
    runTransaction(db, async (transaction) => {
      await transaction.get(friendshipRef(db, "u1~u2"));
      transaction.delete(friendshipRef(db, "u1~u2"));
    }),
  );
});

test("nobody outside the pair can delete a friendship", async () => {
  await seedFriendStudents();
  await seedPendingRequest();

  await assertFails(deleteDoc(friendshipRef(studentDb("u3"), "u1~u2")));
  await assertFails(deleteDoc(friendshipRef(associationDb("verifiedClub"), "u1~u2")));
  await assertFails(deleteDoc(friendshipRef(env.unauthenticatedContext().firestore(), "u1~u2")));
  if ((await storedFriendship("u1~u2")) === null) throw new Error("The friendship was deleted");
});

test("a deleted account can't delete its friendships", async () => {
  await seedFriendStudents();
  await seedPendingRequest();
  await seedDeletedAccount("u1");

  await assertFails(deleteDoc(friendshipRef(studentDb("u1"), "u1~u2")));
});

// ---- chats/{pairId} and chats/{pairId}/messages/{id} (#428, designed in #365) ----
// A chat between two friends, under their friendship's pair ID, holding exactly uids, and its
// messages, with group chat's fields. The two students read it, friends or not; creating it and
// sending need an accepted friendship. These are the cases of docs/Architecture-Diagram.md
// section 3.8 ("Allowed" and "Denied"), from #365's prototype; unsend (#435) and delete for me
// (#437) add theirs.

/** The pair ID of [a] and [b] (#338): the two UIDs sorted and joined by "~". */
function pairOf(a, b) {
  return [a, b].sort().join("~");
}

/** Stores the friendship of [a] and [b], requested by [a], with the rules off. */
async function seedFriends(a, b, status = "accepted") {
  const uids = [a, b].sort();
  await seedFriendship(uids.join("~"), {
    uids,
    requestedBy: a,
    status,
    createdAt: Timestamp.fromMillis(1_790_000_000_000),
    ...(status === "accepted" ? { acceptedAt: Timestamp.fromMillis(1_790_000_001_000) } : {}),
  });
}

/** Stores chats/[pairId] holding [uids], by default the ID's two UIDs, with the rules off. */
async function seedChat(pairId, uids = pairId.split("~")) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `chats/${pairId}`), { uids });
  });
}

function chatMessagePath(pairId, id) {
  return `chats/${pairId}/messages/${id}`;
}

/** Stores the message [id] of chats/[pairId] from [senderId] at [millis], rules off. */
async function seedChatMessage(pairId, id, senderId, millis) {
  await seedMessageAt(chatMessagePath(pairId, id), senderId, millis);
}

/** A chat's history, as the chat screen reads it. */
function chatHistory(db, pairId) {
  return historyAt(db, `chats/${pairId}/messages`);
}

/** A chat's latest message, as the Chats tab reads it. */
function chatLatest(db, pairId) {
  return latestAt(db, `chats/${pairId}/messages`);
}

/** The Chats tab's query: the student's own one-to-one chats. */
function myChats(db, uid) {
  return query(collection(db, "chats"), where("uids", "array-contains", uid));
}

function idsOf(snapshot) {
  return snapshot.docs.map((document) => document.id);
}

/**
 * Sends [message] to chats/[pairId] under the ID [id] the way the repository does (Architecture
 * Diagram 3.8): one transaction, tried once, that reads the chat and the message ID (#238's retry
 * check), writes nothing if the ID already holds a message, and creates the chat with the message
 * if the chat is missing. Returns "sent" or "already stored".
 */
function sendToFriendAs(db, pairId, message, id = "new") {
  return runTransaction(
    db,
    async (transaction) => {
      const chat = doc(db, `chats/${pairId}`);
      const stored = doc(db, chatMessagePath(pairId, id));
      const chatSnapshot = await transaction.get(chat);
      if ((await transaction.get(stored)).exists()) return "already stored";
      if (!chatSnapshot.exists()) transaction.set(chat, { uids: pairId.split("~") });
      transaction.set(stored, message);
      return "sent";
    },
    { maxAttempts: 1 },
  );
}

/** Writes only the message, without reading or creating its chat. */
function setChatMessage(db, pairId, message, id = "new") {
  return setDoc(doc(db, chatMessagePath(pairId, id)), message);
}

// -- the whole flow, through the rules --

test("two students chat once friends, read-only after unfriending, in the same chat once friends again", async () => {
  await seedFriendStudents();
  const request = (from, to) =>
    setDoc(friendshipRef(studentDb(from), "u1~u2"), friendRequest(from, to));
  const accept = (uid) => updateDoc(friendshipRef(studentDb(uid), "u1~u2"), acceptance());
  await assertSucceeds(request("u1", "u2"));
  // Pending: neither starts the chat.
  for (const uid of ["u1", "u2"]) {
    await assertFails(sendToFriendAs(studentDb(uid), "u1~u2", newMessage(uid), "m0"));
  }
  await assertSucceeds(accept("u2"));
  // Friends: the first message creates the chat, the reply only adds a message.
  await assertSucceeds(sendToFriendAs(studentDb("u1"), "u1~u2", newMessage("u1", "Hi"), "m1"));
  assert.deepEqual(await storedAt("chats/u1~u2"), { uids: ["u1", "u2"] });
  await assertSucceeds(sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Hello"), "m2"));
  // u1 removes u2: both keep reading and listing it, neither sends.
  await assertSucceeds(deleteDoc(friendshipRef(studentDb("u1"), "u1~u2")));
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    assert.deepEqual(idsOf(await assertSucceeds(getDocs(chatHistory(db, "u1~u2")))), ["m1", "m2"]);
    assert.deepEqual(idsOf(await assertSucceeds(getDocs(myChats(db, uid)))), ["u1~u2"]);
    await assertSucceeds(getDoc(doc(db, "chats/u1~u2")));
    await assertFails(sendToFriendAs(db, "u1~u2", newMessage(uid), "m3"));
  }
  // A new request is pending: still read-only, for both.
  await assertSucceeds(request("u2", "u1"));
  for (const uid of ["u1", "u2"]) {
    await assertFails(sendToFriendAs(studentDb(uid), "u1~u2", newMessage(uid), "m3"));
  }
  await expectMissing(chatMessagePath("u1~u2", "m3"));
  // Friends again: both send in the same chat, after its history.
  await assertSucceeds(accept("u1"));
  await assertSucceeds(sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Back"), "m3"));
  await assertSucceeds(sendToFriendAs(studentDb("u1"), "u1~u2", newMessage("u1", "Hey"), "m4"));
  assert.deepEqual(
    idsOf(await assertSucceeds(getDocs(chatHistory(studentDb("u2"), "u1~u2")))),
    ["m1", "m2", "m3", "m4"],
  );
});

// -- creating the chat --

for (const uid of ["u1", "u2"]) {
  test(`a friend (${uid}) creates the chat with the first message in one transaction`, async () => {
    await seedFriends("u1", "u2");
    const result = await assertSucceeds(sendToFriendAs(studentDb(uid), "u1~u2", newMessage(uid)));
    assert.equal(result, "sent");
    assert.deepEqual(await storedAt("chats/u1~u2"), { uids: ["u1", "u2"] });
    const message = await storedAt(chatMessagePath("u1~u2", "new"));
    assert.deepEqual([message.senderId, message.text], [uid, "See you at 6?"]);
  });
}

test("a friend creates the chat and the first message in one batch too", async () => {
  await seedFriends("u1", "u2");
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] });
  batch.set(doc(db, chatMessagePath("u1~u2", "new")), newMessage("u1"));
  await assertSucceeds(batch.commit());
});

test("a friend may create the chat document alone (the rules can't require a message)", async () => {
  await seedFriends("u1", "u2");
  await assertSucceeds(setDoc(doc(studentDb("u2"), "chats/u1~u2"), { uids: ["u1", "u2"] }));
});

test("a third student, even a friend of both, can't create, read, list or send in a chat", async () => {
  await seedFriends("u1", "u2");
  await seedFriends("u1", "u3");
  await seedFriends("u2", "u3");
  const db = studentDb("u3");
  // Before it exists: no create, and no read as missing either.
  await assertFails(getDoc(doc(db, "chats/u1~u2")));
  await assertFails(getDocs(chatHistory(db, "u1~u2")));
  await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
  await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u3"] }));
  await assertFails(sendToFriendAs(db, "u1~u2", newMessage("u3")));
  await expectMissing("chats/u1~u2");
  // Once the two started it.
  await seedChat("u1~u2");
  await seedChatMessage("u1~u2", "m1", "u1");
  await assertFails(getDoc(doc(db, "chats/u1~u2")));
  await assertFails(getDocs(myChats(db, "u1")));
  await assertFails(getDoc(doc(db, chatMessagePath("u1~u2", "m1"))));
  await assertFails(getDocs(chatHistory(db, "u1~u2")));
  await assertFails(getDocs(chatLatest(db, "u1~u2")));
  await assertFails(setChatMessage(db, "u1~u2", newMessage("u3")));
  await assertFails(setChatMessage(db, "u1~u2", newMessage("u1")));
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

test("a UID that only resembles a participant's reads and sends nothing", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  await seedChatMessage("u1~u2", "m1", "u1");
  for (const uid of ["u", "u1~", "u1~u2", "u10", "U1"]) {
    const db = studentDb(uid);
    await assertFails(getDoc(doc(db, "chats/u1~u2"))).catch((error) => {
      throw new Error(`${uid} read the chat: ${error.message}`);
    });
    await assertFails(getDocs(chatHistory(db, "u1~u2")));
    await assertFails(setChatMessage(db, "u1~u2", newMessage(uid)));
  }
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

for (const [name, id] of [
  ["the two UIDs unsorted", "u2~u1"],
  ["one UID", "u1"],
  ["three UIDs", "u1~u2~u3"],
  ["the same UID twice", "u1~u1"],
  ["an empty first UID", "~u1"],
  ["an empty second UID", "u1~"],
  ["another separator", "u1_u2"],
  // split('~') drops trailing empty parts in the rules, so these split into the pair [u1, u2].
  ["a trailing separator", "u1~u2~"],
  ["several trailing separators", "u1~u2~~~"],
  ["a doubled separator", "u1~~u2"],
]) {
  test(`a chat whose ID is ${name} (${id}) is refused, even with a friendship stored there`, async () => {
    await seedFriends("u1", "u2");
    await seedFriendship(id, { uids: ["u1", "u2"], requestedBy: "u1", status: "accepted" });
    const db = studentDb("u1");
    await assertFails(setDoc(doc(db, `chats/${id}`), { uids: ["u1", "u2"] }));
    await assertFails(setDoc(doc(db, `chats/${id}`), { uids: id.split("~") }));
    await assertFails(sendToFriendAs(db, id, newMessage("u1")));
    const batch = writeBatch(db);
    batch.set(doc(db, `chats/${id}`), { uids: ["u1", "u2"] });
    batch.set(doc(db, chatMessagePath(id, "new")), newMessage("u1"));
    await assertFails(batch.commit());
    await expectMissing(`chats/${id}`);
    // The pair's real chat still starts, and it is the only one either student lists.
    await assertSucceeds(sendToFriendAs(db, "u1~u2", newMessage("u1"), "real"));
    assert.deepEqual(idsOf(await assertSucceeds(getDocs(myChats(studentDb("u2"), "u2")))), ["u1~u2"]);
    // Even stored there with the rules off, nobody reads it or sends in it.
    await seedChat(id, ["u1", "u2"]);
    await seedChatMessage(id, "m1", "u2");
    for (const uid of ["u1", "u2"]) {
      const other = studentDb(uid);
      await assertFails(getDoc(doc(other, `chats/${id}`)));
      await assertFails(getDoc(doc(other, chatMessagePath(id, "m1"))));
      await assertFails(getDocs(chatHistory(other, id)));
      await assertFails(getDocs(chatLatest(other, id)));
      await assertFails(setChatMessage(other, id, newMessage(uid)));
    }
    await expectMissing(chatMessagePath(id, "new"));
  });
}

test("a student reads a chat with any other UID as missing, and can store nothing there", async () => {
  const db = studentDb("u1");
  for (const id of ["u1~u9", "u0~u1"]) {
    const snapshot = await assertSucceeds(getDoc(doc(db, `chats/${id}`)));
    assert.equal(snapshot.exists(), false);
    assert.equal((await assertSucceeds(getDocs(chatHistory(db, id)))).size, 0);
    await assertFails(setDoc(doc(db, `chats/${id}`), { uids: id.split("~") }));
    await assertFails(sendToFriendAs(db, id, newMessage("u1")));
    await expectMissing(`chats/${id}`);
  }
});

for (const [name, data] of [
  ["the two UIDs in the other order", { uids: ["u2", "u1"] }],
  ["only the caller", { uids: ["u1"] }],
  ["another pair", { uids: ["u1", "u3"] }],
  ["a third UID", { uids: ["u1", "u2", "u3"] }],
  ["uids as a map", { uids: { u1: true, u2: true } }],
  ["uids as a string", { uids: "u1~u2" }],
  ["no uids", {}],
  ["a summary", { uids: ["u1", "u2"], lastMessage: "Hi" }],
  ["a createdAt", { uids: ["u1", "u2"], createdAt: serverTimestamp() }],
]) {
  test(`a chat with ${name} is refused`, async () => {
    await seedFriends("u1", "u2");
    await seedFriends("u1", "u3");
    await assertFails(setDoc(doc(studentDb("u1"), "chats/u1~u2"), data));
    await expectMissing("chats/u1~u2");
  });
}

test("without a friendship, neither student creates the chat or sends", async () => {
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await assertFails(sendToFriendAs(db, "u1~u2", newMessage(uid)));
  }
  await expectMissing("chats/u1~u2");
  // Nor in a chat stored with the rules off.
  await seedChat("u1~u2");
  await assertFails(setChatMessage(studentDb("u1"), "u1~u2", newMessage("u1")));
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

test("a pending request lets neither its sender nor its recipient create the chat or send", async () => {
  await seedFriends("u1", "u2", "pending");
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await assertFails(sendToFriendAs(db, "u1~u2", newMessage(uid)));
  }
  await expectMissing("chats/u1~u2");
  // Nor in an existing chat (a new request after unfriending).
  await seedChat("u1~u2");
  for (const uid of ["u1", "u2"]) {
    await assertFails(sendToFriendAs(studentDb(uid), "u1~u2", newMessage(uid)));
  }
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

for (const [name, changes] of [
  ["no status", { status: undefined }],
  ["a null status", { status: null }],
  ["another status", { status: "blocked" }],
  ["ACCEPTED in capitals", { status: "ACCEPTED" }],
  ["the status true", { status: true }],
]) {
  test(`a friendship with ${name} is no friendship: no chat and no message`, async () => {
    const friendship = { uids: ["u1", "u2"], requestedBy: "u1", ...changes };
    if (friendship.status === undefined) delete friendship.status;
    await seedFriendship("u1~u2", friendship);
    const db = studentDb("u1");
    await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await assertFails(sendToFriendAs(db, "u1~u2", newMessage("u1")));
    await expectMissing("chats/u1~u2");
    await seedChat("u1~u2");
    await assertFails(sendToFriendAs(db, "u1~u2", newMessage("u1")));
    await expectMissing(chatMessagePath("u1~u2", "new"));
  });
}

test("a friendship with someone else opens no chat with a third student", async () => {
  await seedFriends("u1", "u3");
  await seedFriends("u2", "u3");
  const db = studentDb("u1");
  await assertFails(setDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
  await assertFails(sendToFriendAs(db, "u1~u2", newMessage("u1")));
  await expectMissing("chats/u1~u2");
});

test("nobody updates or deletes a chat document, not even rewriting the same content", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  for (const uid of ["u1", "u2", "u3"]) {
    const chat = doc(studentDb(uid), "chats/u1~u2");
    await assertFails(setDoc(chat, { uids: ["u1", "u2"] }));
    await assertFails(updateDoc(chat, { uids: ["u1"] }));
    await assertFails(updateDoc(chat, { uids: ["u1", "u2", "u3"] }));
    await assertFails(updateDoc(chat, { hidden: true }));
    await assertFails(deleteDoc(chat));
  }
  assert.deepEqual(await storedAt("chats/u1~u2"), { uids: ["u1", "u2"] });
});

test("a batch that writes an existing chat again with a message is refused as a whole", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] });
  batch.set(doc(db, chatMessagePath("u1~u2", "new")), newMessage("u1"));
  await assertFails(batch.commit());
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

// -- both send their first message at once --

test("the loser of a first-message race stores nothing, and its second attempt adds only its message", async () => {
  await seedFriends("u1", "u2");
  const db = studentDb("u1");
  // u1 reads the chat as missing; u2's first message creates it before u1 commits.
  const lost = runTransaction(
    db,
    async (transaction) => {
      const chat = doc(db, "chats/u1~u2");
      if ((await transaction.get(chat)).exists()) throw new Error("The chat should be missing");
      // assertSucceeds only passes a refusal on, which would read as u1's lost race below.
      await assertSucceeds(
        sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u2", "First!"), "fromU2"),
      ).catch((error) => {
        throw new Error(`u2's first message was refused: ${error.message}`);
      });
      transaction.set(chat, { uids: ["u1", "u2"] });
      transaction.set(doc(db, chatMessagePath("u1~u2", "fromU1")), newMessage("u1", "Me too"));
    },
    { maxAttempts: 1 },
  );
  const failure = await lost.then(() => null, (error) => error);
  // The transaction's own check that the chat is still missing fails it on the emulator; a commit
  // the rules check first is refused instead, since nobody updates a chat.
  assert.ok(
    ["already-exists", "permission-denied"].includes(failure?.code),
    `The losing commit: ${failure ? `${failure.code} ${failure.message}` : "committed"}`,
  );
  await expectMissing(chatMessagePath("u1~u2", "fromU1"));
  // The second attempt reads the chat as existing and writes only its message.
  const rerun = await assertSucceeds(
    sendToFriendAs(db, "u1~u2", newMessage("u1", "Me too"), "fromU1"),
  );
  assert.equal(rerun, "sent");
  assert.deepEqual(idsOf(await getDocs(chatHistory(db, "u1~u2"))).sort(), ["fromU1", "fromU2"]);
  assert.deepEqual(await storedAt("chats/u1~u2"), { uids: ["u1", "u2"] });
});

// -- reading and listing --

test("both students get the chat document; a chat not created yet reads as missing, with no messages", async () => {
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    assert.equal((await assertSucceeds(getDoc(doc(db, "chats/u1~u2")))).exists(), false);
    assert.equal((await assertSucceeds(getDocs(chatHistory(db, "u1~u2")))).size, 0);
    assert.equal((await assertSucceeds(getDocs(chatLatest(db, "u1~u2")))).size, 0);
  }
  await seedChat("u1~u2");
  for (const uid of ["u1", "u2"]) {
    const chat = await assertSucceeds(getDoc(doc(studentDb(uid), "chats/u1~u2")));
    assert.deepEqual(chat.data(), { uids: ["u1", "u2"] });
  }
});

test("both students read a chat's messages in the server's time order, and its latest one", async () => {
  await seedChat("u1~u2");
  await seedChatMessage("u1~u2", "later", "u2", 1_790_000_060_000);
  await seedChatMessage("u1~u2", "earlier", "u1", 1_790_000_000_000);
  for (const uid of ["u1", "u2"]) {
    const db = studentDb(uid);
    await assertSucceeds(getDoc(doc(db, chatMessagePath("u1~u2", "later"))));
    assert.deepEqual(idsOf(await assertSucceeds(getDocs(chatHistory(db, "u1~u2")))), ["earlier", "later"]);
    assert.deepEqual(idsOf(await assertSucceeds(getDocs(chatLatest(db, "u1~u2")))), ["later"]);
  }
});

test("a student lists exactly their own chats with uids array-contains their UID", async () => {
  await seedChat("u1~u2");
  await seedChat("u1~u3");
  await seedChat("u2~u3");
  expectIds(idsOf(await assertSucceeds(getDocs(myChats(studentDb("u1"), "u1")))), ["u1~u2", "u1~u3"]);
  expectIds(idsOf(await assertSucceeds(getDocs(myChats(studentDb("u3"), "u3")))), ["u1~u3", "u2~u3"]);
  assert.equal((await assertSucceeds(getDocs(myChats(studentDb("u4"), "u4")))).size, 0);
});

test("a query that can only return the caller's own chats is allowed", async () => {
  await seedChat("u1~u2");
  await seedChat("u2~u3");
  const chats = collection(studentDb("u1"), "chats");
  const byPair = query(chats, where("uids", "==", ["u1", "u2"]));
  assert.deepEqual(idsOf(await assertSucceeds(getDocs(byPair))), ["u1~u2"]);
  const byId = query(chats, where(documentId(), "==", "u1~u2"));
  assert.deepEqual(idsOf(await assertSucceeds(getDocs(byId))), ["u1~u2"]);
});

test("nobody lists chats without the filter on their own UID", async () => {
  await seedChat("u1~u2");
  await seedChat("u2~u3");
  const chats = collection(studentDb("u1"), "chats");
  for (const [name, list] of [
    ["the whole collection", chats],
    ["a limit", query(chats, limit(1))],
    ["array-contains another UID", query(chats, where("uids", "array-contains", "u2"))],
    ["array-contains-any", query(chats, where("uids", "array-contains-any", ["u1", "u2"]))],
    ["uids == another pair", query(chats, where("uids", "==", ["u2", "u3"]))],
    ["another chat's ID", query(chats, where(documentId(), "==", "u2~u3"))],
    ["a range of IDs", query(chats, where(documentId(), ">=", "u1~"))],
    ["in on IDs", query(chats, where(documentId(), "in", ["u1~u2", "u2~u3"]))],
    ["an orderBy", query(chats, orderBy("uids"))],
  ]) {
    await assertFails(getDocs(list)).catch((error) => {
      throw new Error(`Listing with ${name} was allowed: ${error.message}`);
    });
  }
});

test("an unfiltered list is refused even when every stored chat is the caller's", async () => {
  await seedChat("u1~u2");
  await seedChat("u1~u3");
  const chats = collection(studentDb("u1"), "chats");
  await assertFails(getDocs(chats));
  await assertFails(getDocs(query(chats, limit(10))));
});

test("nobody queries chats or their messages across chats (collection group)", async () => {
  await seedChat("u1~u2");
  await seedChatMessage("u1~u2", "m1", "u1");
  const db = studentDb("u1");
  await assertFails(getDocs(collectionGroup(db, "messages")));
  await assertFails(getDocs(query(collectionGroup(db, "messages"), where("senderId", "==", "u1"))));
  await assertFails(getDocs(collectionGroup(db, "chats")));
  await assertFails(getDocs(query(collectionGroup(db, "chats"), where("uids", "array-contains", "u1"))));
});

test("no other subcollection of a chat is reachable", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  await seedChatMessage("u1~u2", "m1", "u1");
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "chats/u1~u2/reads/u1"), { at: 1 });
  });
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, "chats/u1~u2/reads/u1")));
  await assertFails(setDoc(doc(db, "chats/u1~u2/reads/u1"), { at: 2 }));
  await assertFails(getDocs(collection(db, "chats/u1~u2/reads")));
  await assertFails(setDoc(doc(db, `${chatMessagePath("u1~u2", "m1")}/replies/r1`), newMessage("u1")));
  // The collection-group list rules of groups and attendees match any path, so such a query under
  // a chat is allowed, and always empty, since nothing can be written there.
  await assertFails(setDoc(doc(db, "chats/u1~u2/groups/g1"), newGroup("u1")));
  const groups = query(collection(db, "chats/u1~u2/groups"), where("memberUids", "array-contains", "u1"));
  assert.equal((await assertSucceeds(getDocs(groups))).size, 0);
  await assertFails(setDoc(doc(db, "chats/u1~u2/attendees/u1"), { uid: "u1" }));
  const attendees = query(collection(db, "chats/u1~u2/attendees"), where("uid", "==", "u1"));
  assert.equal((await assertSucceeds(getDocs(attendees))).size, 0);
});

// -- sending --

test("a friend sends in an existing chat, as the app does", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  await assertSucceeds(sendToFriendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
  await assertSucceeds(sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u2", "Yes!"), "reply"));
  const message = await storedAt(chatMessagePath("u1~u2", "new"));
  assert.deepEqual([message.senderId, message.text], ["u1", "See you at 6?"]);
  assert.deepEqual(await storedAt("chats/u1~u2"), { uids: ["u1", "u2"] });
});

test("a student can't send as the other student, nor with the deleted-author marker", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  await assertFails(sendToFriendAs(studentDb("u1"), "u1~u2", newMessage("u2")));
  await assertFails(sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u1")));
  await assertFails(sendToFriendAs(studentDb("u1"), "u1~u2", newMessage("")));
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

test("after unfriending, neither student sends", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  await assertSucceeds(sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u2"), "before"));
  await assertSucceeds(deleteDoc(friendshipRef(studentDb("u2"), "u1~u2")));
  for (const uid of ["u1", "u2"]) {
    await assertFails(sendToFriendAs(studentDb(uid), "u1~u2", newMessage(uid)));
  }
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

// areFriends() reads the friendship as it was before the write (get(), not getAfter()), as group
// chat's rules read the group (Architecture Diagram 3.8, Denied 24), so a batch that also changes
// the friendship is judged by the one it found.
test("accepting a request and sending in one batch is refused: the request was still pending", async () => {
  await seedPendingRequest();
  const db = studentDb("u2");
  const batch = writeBatch(db);
  batch.update(friendshipRef(db, "u1~u2"), acceptance());
  batch.set(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] });
  batch.set(doc(db, chatMessagePath("u1~u2", "new")), newMessage("u2"));
  await assertFails(batch.commit());
  await expectMissing("chats/u1~u2");
  // Accepted first, the same first message passes.
  await assertSucceeds(updateDoc(friendshipRef(db, "u1~u2"), acceptance()));
  await assertSucceeds(sendToFriendAs(db, "u1~u2", newMessage("u2")));
});

// The same as sending just before removing the friend.
test("removing a friend and sending in one batch passes: the friendship was still accepted", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.delete(friendshipRef(db, "u1~u2"));
  batch.set(doc(db, chatMessagePath("u1~u2", "last")), newMessage("u1"));
  await assertSucceeds(batch.commit());
  await assertFails(sendToFriendAs(db, "u1~u2", newMessage("u1")));
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

test("a message whose chat doesn't exist and isn't created with it is refused", async () => {
  await seedFriends("u1", "u2");
  await assertFails(setChatMessage(studentDb("u1"), "u1~u2", newMessage("u1")));
  await expectMissing(chatMessagePath("u1~u2", "new"));
});

for (const [name, changes] of REFUSED_MESSAGE_CHANGES) {
  test(`a one-to-one message with ${name} is refused, also as the chat's first`, async () => {
    await seedFriends("u1", "u2");
    const message = messageWith("u1", changes);
    await assertFails(sendToFriendAs(studentDb("u1"), "u1~u2", message));
    await expectMissing("chats/u1~u2");
    await seedChat("u1~u2");
    await assertFails(sendToFriendAs(studentDb("u1"), "u1~u2", message));
    await expectMissing(chatMessagePath("u1~u2", "new"));
  });
}

test("a one-to-one message may have 1 or 1000 characters, or 500 emoji", async () => {
  await seedFriends("u1", "u2");
  const db = studentDb("u1");
  await assertSucceeds(sendToFriendAs(db, "u1~u2", newMessage("u1", "a"), "shortest"));
  await assertSucceeds(sendToFriendAs(db, "u1~u2", newMessage("u1", "a".repeat(1000)), "longest"));
  await assertSucceeds(sendToFriendAs(db, "u1~u2", newMessage("u1", EMOJI.repeat(500)), "emoji"));
});

test("a retry under the same message ID reads the stored message; writing it again is refused", async () => {
  await seedFriends("u1", "u2");
  const db = studentDb("u1");
  assert.equal(await assertSucceeds(sendToFriendAs(db, "u1~u2", newMessage("u1", "Once"), "draft")), "sent");
  assert.equal(
    await assertSucceeds(sendToFriendAs(db, "u1~u2", newMessage("u1", "Once"), "draft")),
    "already stored",
  );
  await assertFails(setChatMessage(db, "u1~u2", newMessage("u1", "Once"), "draft"));
});

test("nobody edits, overwrites or deletes a one-to-one message, not even its sender", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  await seedChatMessage("u1~u2", "m1", "u1");
  for (const uid of ["u1", "u2", "u3"]) {
    const message = doc(studentDb(uid), chatMessagePath("u1~u2", "m1"));
    await assertFails(updateDoc(message, { text: "Edited" }));
    await assertFails(updateDoc(message, { senderId: "" }));
    await assertFails(setDoc(message, newMessage("u1", "Overwritten")));
    await assertFails(deleteDoc(message));
  }
  assert.equal((await storedAt(chatMessagePath("u1~u2", "m1"))).text, "Seeded");
});

// -- deleted accounts --

test("a caller under a deletion marker can't read, list, create or send; the other student still reads", async () => {
  await seedFriends("u1", "u2");
  await seedFriends("u1", "u3");
  await seedChat("u1~u2");
  await seedChatMessage("u1~u2", "m1", "u1");
  await assertSucceeds(getDocs(chatHistory(studentDb("u1"), "u1~u2")));
  await seedDeletedAccount("u1");
  const db = studentDb("u1");
  await assertFails(getDoc(doc(db, "chats/u1~u2")));
  await assertFails(getDocs(myChats(db, "u1")));
  await assertFails(getDoc(doc(db, chatMessagePath("u1~u2", "m1"))));
  await assertFails(getDocs(chatHistory(db, "u1~u2")));
  await assertFails(getDocs(chatLatest(db, "u1~u2")));
  await assertFails(sendToFriendAs(db, "u1~u2", newMessage("u1")));
  // The transaction already stops at its read: the message alone checks the create rule.
  await assertFails(setChatMessage(db, "u1~u2", newMessage("u1")));
  // Its friendship with u3 is still stored (deleteAccount hasn't reached it yet): no new chat either.
  await assertFails(setDoc(doc(db, "chats/u1~u3"), { uids: ["u1", "u3"] }));
  await assertFails(sendToFriendAs(db, "u1~u3", newMessage("u1")));
  await expectMissing(chatMessagePath("u1~u2", "new"));
  await expectMissing("chats/u1~u3");
  const other = studentDb("u2");
  await assertSucceeds(getDoc(doc(other, "chats/u1~u2")));
  assert.deepEqual(idsOf(await assertSucceeds(getDocs(myChats(other, "u2")))), ["u1~u2"]);
  assert.deepEqual(idsOf(await assertSucceeds(getDocs(chatHistory(other, "u1~u2")))), ["m1"]);
});

test("until deleteAccount deletes the friendship, the other student still sends", async () => {
  await seedFriends("u1", "u2");
  await seedChat("u1~u2");
  await seedDeletedAccount("u1");
  await assertSucceeds(sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u2")));
  await assertFails(getDocs(chatHistory(studentDb("u1"), "u1~u2")));
});

test("another student's deletion marker doesn't stop two friends chatting", async () => {
  await seedFriends("u1", "u2");
  await seedDeletedAccount("u3");
  await assertSucceeds(sendToFriendAs(studentDb("u1"), "u1~u2", newMessage("u1")));
  await assertSucceeds(sendToFriendAs(studentDb("u2"), "u1~u2", newMessage("u2"), "reply"));
});

test("once the other account is deleted, the remaining student lists and reads the chat but can't send (#429)", async () => {
  // What deleteAccount leaves of u1: the marker, no friendship, an empty senderId on u1's
  // messages, and u1 taken out of uids.
  await seedDeletedAccount("u1");
  await seedChat("u1~u2", ["u2"]);
  await seedChatMessage("u1~u2", "theirs", "", 1_790_000_000_000);
  await seedChatMessage("u1~u2", "mine", "u2", 1_790_000_060_000);
  const db = studentDb("u2");
  assert.deepEqual(idsOf(await assertSucceeds(getDocs(myChats(db, "u2")))), ["u1~u2"]);
  assert.deepEqual((await assertSucceeds(getDoc(doc(db, "chats/u1~u2")))).data(), { uids: ["u2"] });
  const history = await assertSucceeds(getDocs(chatHistory(db, "u1~u2")));
  assert.deepEqual(history.docs.map((message) => message.data().senderId), ["", "u2"]);
  assert.deepEqual(idsOf(await assertSucceeds(getDocs(chatLatest(db, "u1~u2")))), ["mine"]);
  await assertFails(sendToFriendAs(db, "u1~u2", newMessage("u2")));
  await assertFails(updateDoc(doc(db, chatMessagePath("u1~u2", "theirs")), { senderId: "u1" }));
  await assertFails(updateDoc(doc(db, "chats/u1~u2"), { uids: ["u1", "u2"] }));
  await assertFails(deleteDoc(doc(db, "chats/u1~u2")));
  // The deleted account's still-valid token reads nothing.
  await assertFails(getDoc(doc(studentDb("u1"), "chats/u1~u2")));
  await assertFails(getDocs(chatHistory(studentDb("u1"), "u1~u2")));
});

// -- accounts that aren't verified students --

for (const verified of [false, true]) {
  const kind = verified ? "verified" : "unverified";
  const clubUid = verified ? "verifiedClub" : "club";
  for (const [name, uid, db, seed] of [
    ["a non-EPFL association", clubUid, () => associationDb(clubUid), async () => {}],
    ["an @epfl.ch association", "epflClub", () => epflAssociationDb("epflClub"),
      () => seedUser("epflClub", { accountType: "association", isAssociationVerified: verified })],
  ]) {
    test(`${name} (${kind}) gets nothing of a chat, even named in it and in a friendship`, async () => {
      await seed();
      const pairId = pairOf(uid, "u1");
      await seedFriends(uid, "u1");
      // Before it exists: no read as missing, no create.
      await assertFails(getDoc(doc(db(), `chats/${pairId}`)));
      await assertFails(setDoc(doc(db(), `chats/${pairId}`), { uids: pairId.split("~") }));
      await assertFails(sendToFriendAs(db(), pairId, newMessage(uid)));
      await expectMissing(`chats/${pairId}`);
      // Stored with the rules off: still nothing.
      await seedChat(pairId);
      await seedChatMessage(pairId, "m1", "u1");
      await assertFails(getDoc(doc(db(), `chats/${pairId}`)));
      await assertFails(getDocs(myChats(db(), uid)));
      await assertFails(getDoc(doc(db(), chatMessagePath(pairId, "m1"))));
      await assertFails(getDocs(chatHistory(db(), pairId)));
      await assertFails(getDocs(chatLatest(db(), pairId)));
      await assertFails(setChatMessage(db(), pairId, newMessage(uid)));
      await expectMissing(chatMessagePath(pairId, "new"));
    });
  }
}

for (const [name, db] of [
  ["an unverified @epfl.ch student", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a student without an email_verified claim",
    () => env.authenticatedContext("u1", { email: "student@epfl.ch" }).firestore()],
  ["a verified non-EPFL account with a student's profile", () => firestoreAs("student@gmail.com", true, "u1")],
  ["a signed-out user", () => env.unauthenticatedContext().firestore()],
]) {
  test(`${name} gets nothing of a one-to-one chat`, async () => {
    await seedFriends("u1", "u2");
    await assertFails(getDoc(doc(db(), "chats/u1~u2")));
    await assertFails(setDoc(doc(db(), "chats/u1~u2"), { uids: ["u1", "u2"] }));
    await expectMissing("chats/u1~u2");
    await seedChat("u1~u2");
    await seedChatMessage("u1~u2", "m1", "u2");
    await assertFails(getDoc(doc(db(), "chats/u1~u2")));
    await assertFails(getDocs(myChats(db(), "u1")));
    await assertFails(getDoc(doc(db(), chatMessagePath("u1~u2", "m1"))));
    await assertFails(getDocs(chatHistory(db(), "u1~u2")));
    await assertFails(setChatMessage(db(), "u1~u2", newMessage("u1")));
    await expectMissing(chatMessagePath("u1~u2", "new"));
  });
}

// ---- events/{eventId}/checkIns/{uid} (#467, designed in #374) ----
// A check-in at the venue, by location for now (the QR method comes with #468): a registered
// student, during the window (30 min before the start to the end, or start + 3 h without one),
// as themselves, with exactly uid, the server's checkedInAt, method "location" and the expiresAt
// the rules compute (the window's end + 30 days). Read by the student and their group for that
// event; no list, no update; the student deletes their own. Times are relative to now, since the
// emulator compares them with the real request.time: each test computes its own when it runs, with
// margins of a minute or more, since the whole file takes minutes.

/** u1's check-in path at [eventId]. */
function checkInPath(eventId, uid = "u1") {
  return `events/${eventId}/checkIns/${uid}`;
}

/** [time] + [ms] milliseconds, keeping [time]'s sub-millisecond part. */
function plusMillis(time, ms) {
  const total = time.nanoseconds + (ms % 1000) * 1_000_000;
  return new Timestamp(
    time.seconds + Math.floor(ms / 1000) + Math.floor(total / 1_000_000_000),
    total % 1_000_000_000,
  );
}

const HOURS_3 = 3 * 3_600_000;
const DAYS_30 = 30 * 86_400_000;

/** The expiresAt the rules require for an event [times]: the window's end + 30 days. */
function expiresAtFor({ startTime, endTime }) {
  return plusMillis(endTime ?? plusMillis(startTime, HOURS_3), DAYS_30);
}

/** A location check-in as FirestoreCheckInRepository writes it for an event [times]. */
function locationCheckIn(uid, times, overrides = {}) {
  return {
    uid,
    checkedInAt: serverTimestamp(),
    method: "location",
    expiresAt: expiresAtFor(times),
    ...overrides,
  };
}

/** An event that started 10 minutes ago and ends in an hour: inside the window now. */
function live() {
  return { startTime: minutesFromNow(-10), endTime: minutesFromNow(60) };
}

/** Seeds u2's event [id] with [times] and [registered] students (rules off). */
async function seedCheckInEvent(id, times = live(), registered = ["u1"], overrides = {}) {
  await seedEvent(id, eventBy("u2", { ...times, ...overrides }));
  for (const uid of registered) await seedAttendee(id, uid);
}

/** Stores [uid]'s location check-in at [eventId], rules off. */
async function seedCheckIn(eventId, uid, times = live()) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), checkInPath(eventId, uid)), {
      uid,
      checkedInAt: Timestamp.fromMillis(1_790_000_000_000),
      method: "location",
      expiresAt: expiresAtFor(times),
    });
  });
}

/** [uid] checks in at [eventId] with [data] (a plain set, as the rules see the app's write). */
function checkInAs(db, eventId, uid, data) {
  return setDoc(doc(db, checkInPath(eventId, uid)), data);
}

/**
 * [uid] checks in as FirestoreCheckInRepository.checkIn does: one transaction that reads the event,
 * the check-in and the registration, then writes the check-in.
 */
function checksInAsApp(uid, eventId, times) {
  const db = studentDb(uid);
  return runTransaction(db, async (transaction) => {
    await transaction.get(doc(db, `events/${eventId}`));
    await transaction.get(doc(db, checkInPath(eventId, uid)));
    await transaction.get(doc(db, `events/${eventId}/attendees/${uid}`));
    transaction.set(doc(db, checkInPath(eventId, uid)), locationCheckIn(uid, times));
  });
}

/** Whether [uid]'s check-in at [eventId] is stored, read with the rules off. */
async function checkInStored(eventId, uid = "u1") {
  let exists;
  await env.withSecurityRulesDisabled(async (context) => {
    exists = (await getDoc(doc(context.firestore(), checkInPath(eventId, uid)))).exists();
  });
  return exists;
}

// -- creating a check-in --

test("a registered student checks in during the window, as the app does", async () => {
  const times = live();
  await seedCheckInEvent("live", times);
  await assertSucceeds(checksInAsApp("u1", "live", times));
  assert.equal(await checkInStored("live"), true);
});

test("the stored check-in has the server's time", async () => {
  const times = live();
  await seedCheckInEvent("live", times);
  await checkInAs(studentDb("u1"), "live", "u1", locationCheckIn("u1", times));
  await env.withSecurityRulesDisabled(async (context) => {
    const stored = (await getDoc(doc(context.firestore(), checkInPath("live")))).data();
    assert.ok(Math.abs(stored.checkedInAt.toMillis() - Date.now()) < 60_000);
  });
});

test("a student who isn't registered can't check in", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u3"]);
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", locationCheckIn("u1", times)));
  assert.equal(await checkInStored("live"), false);
});

test("a student can't check in at an event that doesn't exist, even registered", async () => {
  const times = live();
  await seedAttendee("gone", "u1");
  await assertFails(checkInAs(studentDb("u1"), "gone", "u1", locationCheckIn("u1", times)));
});

test("a student in a private event's readers checks in", async () => {
  const times = live();
  await seedCheckInEvent("p", times, ["u1"], { isPrivate: true, allowedUids: ["u2", "u1"] });
  await assertSucceeds(checkInAs(studentDb("u1"), "p", "u1", locationCheckIn("u1", times)));
});

test("a student removed from a private event's readers can't check in, though registered", async () => {
  // Their registration stays, so they can always cancel (#295), but the app can't read the event
  // and says NotFound; the rules agree (#467 review).
  const times = live();
  await seedCheckInEvent("p", times, ["u1"], { isPrivate: true, allowedUids: ["u2"] });
  await assertFails(checkInAs(studentDb("u1"), "p", "u1", locationCheckIn("u1", times)));
  assert.equal(await checkInStored("p"), false);
});

test("a student can't check another registered student in", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1", "u3"]);
  await assertFails(checkInAs(studentDb("u1"), "live", "u3", locationCheckIn("u3", times)));
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", locationCheckIn("u3", times)));
  assert.equal(await checkInStored("live", "u3"), false);
});

test("a deleted account can't check in", async () => {
  const times = live();
  await seedCheckInEvent("live", times);
  await seedDeletedAccount("u1");
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", locationCheckIn("u1", times)));
});

for (const [name, db] of [
  ["an unverified @epfl.ch account", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a non-EPFL account", () => associationDb("u1")],
]) {
  test(`${name} can't check in, even with a registration`, async () => {
    const times = live();
    await seedCheckInEvent("live", times);
    await assertFails(checkInAs(db(), "live", "u1", locationCheckIn("u1", times)));
  });
}

test("an @epfl.ch association can't check in, even with a registration", async () => {
  const times = live();
  await seedCheckInEvent("live", times);
  await seedEpflAssociation("u1", true);
  await assertFails(checkInAs(epflAssociationDb("u1"), "live", "u1", locationCheckIn("u1", times)));
});

for (const [name, timesNow] of [
  [
    "29 minutes before the start",
    () => ({ startTime: minutesFromNow(29), endTime: minutesFromNow(120) }),
  ],
  [
    "just before the end",
    () => ({ startTime: minutesFromNow(-120), endTime: minutesFromNow(1) }),
  ],
  [
    "without an end time, 2 h 50 after the start",
    () => ({ startTime: minutesFromNow(-170), endTime: null }),
  ],
  ["with an end time more than 3 h after the start", () => ({
    startTime: minutesFromNow(-200), endTime: minutesFromNow(10),
  })],
]) {
  test(`a registered student can check in ${name}`, async () => {
    const times = timesNow();
    await seedCheckInEvent("e", times);
    await assertSucceeds(checkInAs(studentDb("u1"), "e", "u1", locationCheckIn("u1", times)));
  });
}

for (const [name, timesNow] of [
  [
    "31 minutes before the start",
    () => ({ startTime: minutesFromNow(31), endTime: minutesFromNow(120) }),
  ],
  [
    "once the end has passed",
    () => ({ startTime: minutesFromNow(-120), endTime: minutesFromNow(-1) }),
  ],
  [
    "without an end time, 3 h 01 after the start",
    () => ({ startTime: minutesFromNow(-181), endTime: null }),
  ],
  ["with an end time, before the start's 3 h have passed", () => ({
    startTime: minutesFromNow(-60), endTime: minutesFromNow(-1),
  })],
]) {
  test(`a registered student can't check in ${name}`, async () => {
    const times = timesNow();
    await seedCheckInEvent("e", times);
    await assertFails(checkInAs(studentDb("u1"), "e", "u1", locationCheckIn("u1", times)));
  });
}

for (const [name, startTime] of [["without a start time", null], ["with a text start", "now"]]) {
  test(`a student can't check in at an event ${name}`, async () => {
    await seedCheckInEvent("e", { startTime, endTime: minutesFromNow(60) });
    await assertFails(checkInAs(studentDb("u1"), "e", "u1", locationCheckIn("u1", live())));
  });
}

test("without a timestamp end time, the check-in window closes 3 h after the start, as in the app", async () => {
  const startTime = minutesFromNow(-10);
  await seedCheckInEvent("e", { startTime, endTime: "tonight" });
  await assertSucceeds(checkInAs(studentDb("u1"), "e", "u1",
    locationCheckIn("u1", { startTime, endTime: null })));
});

test("the check-in's time must be the server's", async () => {
  const times = live();
  await seedCheckInEvent("live", times);
  // A fixed past time, so it can never equal request.time (#522).
  const clientTime = Timestamp.fromMillis(Date.now() - 60_000);
  await assertFails(checkInAs(studentDb("u1"), "live", "u1",
    locationCheckIn("u1", times, { checkedInAt: clientTime })));
});

for (const [name, expiryFor] of [
  ["a millisecond later", (times) => plusMillis(expiresAtFor(times), 1)],
  ["a day earlier", (times) => plusMillis(expiresAtFor(times), -86_400_000)],
  ["a year later", (times) => plusMillis(expiresAtFor(times), 365 * 86_400_000)],
  ["missing", () => undefined],
  ["as text", () => "2099-01-01"],
]) {
  test(`a check-in can't expire ${name}`, async () => {
    const times = live();
    await seedCheckInEvent("live", times);
    const expiresAt = expiryFor(times);
    const data = locationCheckIn("u1", times, { expiresAt });
    if (expiresAt === undefined) delete data.expiresAt;
    await assertFails(checkInAs(studentDb("u1"), "live", "u1", data));
  });
}

test("the expiry keeps the end time's sub-millisecond part", async () => {
  const end = minutesFromNow(60);
  const times = { startTime: minutesFromNow(-10), endTime: new Timestamp(end.seconds, 123_456_000) };
  await seedCheckInEvent("precise", times);
  const truncated = plusMillis(new Timestamp(end.seconds, 123_000_000), DAYS_30);
  await assertFails(checkInAs(studentDb("u1"), "precise", "u1",
    locationCheckIn("u1", times, { expiresAt: truncated })));
  await assertSucceeds(checkInAs(studentDb("u1"), "precise", "u1", locationCheckIn("u1", times)));
});

for (const [name, overrides] of [
  ["a QR method without an entrance code", { method: "qr", token: "AAAAAAAAAAAAAAAAAAAAAA" }],
  ["a QR method without a token", { method: "qr" }],
  ["another method", { method: "gps" }],
  ["a token with the location method", { token: "AAAAAAAAAAAAAAAAAAAAAA" }],
  ["coordinates", { latitude: 46.5184, longitude: 6.568 }],
  ["an accuracy", { accuracy: 12 }],
]) {
  test(`a check-in can't have ${name}`, async () => {
    const times = live();
    await seedCheckInEvent("live", times);
    await assertFails(checkInAs(studentDb("u1"), "live", "u1",
      locationCheckIn("u1", times, overrides)));
  });
}

for (const field of ["uid", "checkedInAt", "method"]) {
  test(`a check-in needs its ${field}`, async () => {
    const times = live();
    await seedCheckInEvent("live", times);
    const data = locationCheckIn("u1", times);
    delete data[field];
    await assertFails(checkInAs(studentDb("u1"), "live", "u1", data));
  });
}

test("nobody updates a check-in, not even its student", async () => {
  const times = live();
  await seedCheckInEvent("live", times);
  await seedCheckIn("live", "u1", times);
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", locationCheckIn("u1", times)));
  await assertFails(updateDoc(doc(studentDb("u1"), checkInPath("live")), { method: "location" }));
});

// -- reading a check-in --

test("a student reads their own check-in, and their own missing one", async () => {
  await seedCheckInEvent("live");
  await assertSucceeds(getDoc(doc(studentDb("u1"), checkInPath("live"))));
  await seedCheckIn("live", "u1");
  await assertSucceeds(getDoc(doc(studentDb("u1"), checkInPath("live"))));
});

test("a member of the student's group reads their check-in, even a missing one", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1", "u3"]);
  await seedGroup("live", "g", ["u1", "u3"]);
  await assertSucceeds(getDoc(doc(studentDb("u3"), checkInPath("live"))));
  await seedCheckIn("live", "u1", times);
  await assertSucceeds(getDoc(doc(studentDb("u3"), checkInPath("live"))));
});

test("a student in another group of the event can't read the check-in", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1", "u3"]);
  await seedGroup("live", "g1", ["u1"]);
  await seedGroup("live", "g2", ["u3"]);
  await seedCheckIn("live", "u1", times);
  await assertFails(getDoc(doc(studentDb("u3"), checkInPath("live"))));
});

test("the same group ID at another event doesn't give access", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1"]);
  await seedCheckInEvent("other", times, ["u3"]);
  await seedGroup("live", "g", ["u1"]);
  await seedGroup("other", "g", ["u3"]);
  await seedCheckIn("live", "u1", times);
  await assertFails(getDoc(doc(studentDb("u3"), checkInPath("live"))));
});

test("a student in no group, or reading someone in none, can't read a check-in", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1", "u3"]);
  await seedCheckIn("live", "u1", times);
  await assertFails(getDoc(doc(studentDb("u3"), checkInPath("live"))));
  await seedGroup("live", "g", ["u3"]);
  await assertFails(getDoc(doc(studentDb("u3"), checkInPath("live"))));
});

test("the event's organizer can't read its check-ins", async () => {
  await seedCheckInEvent("live");
  await seedCheckIn("live", "u1");
  await assertFails(getDoc(doc(studentDb("u2"), checkInPath("live"))));
});

test("a deleted account can't read check-ins, not even its own", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1", "u3"]);
  await seedGroup("live", "g", ["u1", "u3"]);
  await seedCheckIn("live", "u1", times);
  await seedDeletedAccount("u1");
  await assertFails(getDoc(doc(studentDb("u1"), checkInPath("live"))));
  await seedDeletedAccount("u3");
  await assertFails(getDoc(doc(studentDb("u3"), checkInPath("live"))));
});

test("a deleted account can't read a live group member's check-in", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1", "u3"]);
  await seedGroup("live", "g", ["u1", "u3"]);
  await seedCheckIn("live", "u1", times);
  await assertSucceeds(getDoc(doc(studentDb("u3"), checkInPath("live"))));
  await seedDeletedAccount("u3");
  await assertFails(getDoc(doc(studentDb("u3"), checkInPath("live"))));
});

test("an association can't read a check-in, even its own UID's", async () => {
  await seedCheckInEvent("live");
  await seedCheckIn("live", "u1");
  await assertFails(getDoc(doc(associationDb("u1"), checkInPath("live"))));
});

test("nobody lists check-ins, not even their own", async () => {
  await seedCheckInEvent("live");
  await seedCheckIn("live", "u1");
  const db = studentDb("u1");
  await assertFails(getDocs(collection(db, "events/live/checkIns")));
  await assertFails(getDocs(query(collection(db, "events/live/checkIns"), where("uid", "==", "u1"))));
  await assertFails(getDocs(query(collectionGroup(db, "checkIns"), where("uid", "==", "u1"))));
});

// -- deleting a check-in --

test("a student deletes their own check-in, and their own missing one", async () => {
  await seedCheckInEvent("live");
  await seedCheckIn("live", "u1");
  await assertSucceeds(deleteDoc(doc(studentDb("u1"), checkInPath("live"))));
  assert.equal(await checkInStored("live"), false);
  await assertSucceeds(deleteDoc(doc(studentDb("u1"), checkInPath("live"))));
});

test("nobody deletes another student's check-in, not even their group or the organizer", async () => {
  const times = live();
  await seedCheckInEvent("live", times, ["u1", "u3"]);
  await seedGroup("live", "g", ["u1", "u3"]);
  await seedCheckIn("live", "u1", times);
  await assertFails(deleteDoc(doc(studentDb("u3"), checkInPath("live"))));
  await assertFails(deleteDoc(doc(studentDb("u2"), checkInPath("live"))));
  await assertFails(deleteDoc(doc(associationDb("u1"), checkInPath("live"))));
  assert.equal(await checkInStored("live"), true);
});

test("cancelling deletes the registration and the check-in in one write, as the app does", async () => {
  await seedCheckInEvent("live");
  await seedCheckIn("live", "u1");
  const db = studentDb("u1");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    transaction.delete(doc(db, "events/live/attendees/u1"));
    transaction.delete(doc(db, checkInPath("live")));
    transaction.update(doc(db, "events/live"), { attendeeCount: increment(-1) });
  }));
  assert.equal(await checkInStored("live"), false);
});

test("the fallback cancel deletes the check-in too, once the event is gone", async () => {
  await seedCheckInEvent("live");
  await seedCheckIn("live", "u1");
  await env.withSecurityRulesDisabled(async (context) => {
    await deleteDoc(doc(context.firestore(), "events/live"));
  });
  const db = studentDb("u1");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    transaction.delete(doc(db, "events/live/attendees/u1"));
    transaction.delete(doc(db, checkInPath("live")));
  }));
  assert.equal(await checkInStored("live"), false);
});

// ---- events/{eventId}/checkInCode/current and QR check-ins (#468, designed in #374) ----
// The entrance code an organizer shows: token (22 base64url characters), previousToken,
// rotatedAt (the server's time), previousRotatedAt (when previousToken was issued) and expiresAt
// (the window's end + 30 days). Only the event's organizers read and write it, during the window;
// a rotation keeps the stored token and rotatedAt as previousToken and previousRotatedAt, and
// comes at least 50 s after the last one. A QR check-in carries the current or previous token,
// each less than 2 minutes after it was issued.

const TOKEN_A = "a".repeat(11) + "-_".repeat(5) + "A";
const TOKEN_B = "B".repeat(21) + "9";
const TOKEN_C = "c".repeat(22);

function codePath(eventId) {
  return `events/${eventId}/checkInCode/current`;
}

/** A first code for an event [times], as FirestoreCheckInRepository writes it. */
function newCode(times, overrides = {}) {
  return {
    token: TOKEN_A,
    rotatedAt: serverTimestamp(),
    expiresAt: expiresAtFor(times),
    ...overrides,
  };
}

/**
 * Stores the code of [eventId], rotated [secondsAgo] seconds ago, rules off, and returns it. A
 * [previousToken] was issued [previousSecondsAgo] seconds ago, by default 60 s before the rotation.
 */
async function seedCode(
  eventId,
  times,
  { token = TOKEN_A, previousToken, secondsAgo = 10, previousSecondsAgo = secondsAgo + 60 } = {},
) {
  const code = {
    token,
    rotatedAt: Timestamp.fromMillis(Date.now() - secondsAgo * 1000),
    expiresAt: expiresAtFor(times),
  };
  if (previousToken !== undefined) {
    code.previousToken = previousToken;
    code.previousRotatedAt = Timestamp.fromMillis(Date.now() - previousSecondsAgo * 1000);
  }
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), codePath(eventId)), code);
  });
  return code;
}

/** The rotation the app writes over the [stored] code: its token and rotatedAt become previous. */
function rotation(times, stored, overrides = {}) {
  return newCode(times, {
    token: TOKEN_B,
    previousToken: stored.token,
    previousRotatedAt: stored.rotatedAt,
    ...overrides,
  });
}

/** The stored code of [eventId], read with the rules off, or null. */
async function storedCode(eventId) {
  let code = null;
  await env.withSecurityRulesDisabled(async (context) => {
    const snapshot = await getDoc(doc(context.firestore(), codePath(eventId)));
    code = snapshot.exists() ? snapshot.data() : null;
  });
  return code;
}

/** u2's live event "live", u5 co-organizing it, u1 and u3 registered. */
async function seedOrganizedEvent(times) {
  await seedCheckInEvent("live", times, ["u1", "u3"], {
    organizerIds: ["u2", "u5"],
    allowedUids: ["u2", "u5"],
  });
}

// -- the entrance code --

test("an organizer creates the entrance code during the window, as the app does", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  const db = studentDb("u2");
  await assertSucceeds(runTransaction(db, async (transaction) => {
    await transaction.get(doc(db, "events/live"));
    await transaction.get(doc(db, codePath("live")));
    transaction.set(doc(db, codePath("live")), newCode(times));
  }));
  assert.equal((await storedCode("live")).token, TOKEN_A);
});

test("a co-organizer reads and rotates the code another organizer created", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  const stored = await seedCode("live", times, { secondsAgo: 61 });
  await assertSucceeds(getDoc(doc(studentDb("u5"), codePath("live"))));
  await assertSucceeds(setDoc(doc(studentDb("u5"), codePath("live")), rotation(times, stored)));
  const code = await storedCode("live");
  assert.equal(code.token, TOKEN_B);
  assert.equal(code.previousToken, TOKEN_A);
  assert.ok(code.previousRotatedAt.isEqual(stored.rotatedAt));
});

test("a verified association organizer shows its event's code", async () => {
  const times = live();
  await seedEvent("club", eventBy("verifiedClub", {
    ...times, isAssociationEvent: true, organizerIds: ["verifiedClub"],
  }));
  const db = associationDb("verifiedClub");
  await assertSucceeds(setDoc(doc(db, codePath("club")), newCode(times)));
  await assertSucceeds(getDoc(doc(db, codePath("club"))));
});

test("nobody but an organizer reads or writes the code, not even a student going", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  for (const db of [studentDb("u1"), studentDb("u4"), associationDb("verifiedClub")]) {
    await assertFails(setDoc(doc(db, codePath("live")), newCode(times)));
  }
  const stored = await seedCode("live", times, { secondsAgo: 61 });
  for (const db of [studentDb("u1"), studentDb("u4"), associationDb("verifiedClub")]) {
    await assertFails(getDoc(doc(db, codePath("live"))));
    await assertFails(setDoc(doc(db, codePath("live")), rotation(times, stored)));
  }
  assert.equal((await storedCode("live")).token, TOKEN_A);
});

test("an organizer under a deletion marker or with an unverified email gets nothing", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await assertFails(setDoc(doc(firestoreAs("student@epfl.ch", false, "u2"), codePath("live")),
    newCode(times)));
  await seedDeletedAccount("u2");
  await assertFails(setDoc(doc(studentDb("u2"), codePath("live")), newCode(times)));
});

for (const [name, timesNow] of [
  ["31 minutes before the start", () => ({
    startTime: minutesFromNow(31), endTime: minutesFromNow(120),
  })],
  ["once the event has ended", () => ({
    startTime: minutesFromNow(-120), endTime: minutesFromNow(-1),
  })],
]) {
  test(`an organizer can't create or read the code ${name}`, async () => {
    const times = timesNow();
    await seedOrganizedEvent(times);
    await assertFails(setDoc(doc(studentDb("u2"), codePath("live")), newCode(times)));
    await seedCode("live", times);
    await assertFails(getDoc(doc(studentDb("u2"), codePath("live"))));
  });
}

test("an organizer creates the code 29 minutes before the start", async () => {
  const times = { startTime: minutesFromNow(29), endTime: minutesFromNow(120) };
  await seedOrganizedEvent(times);
  await assertSucceeds(setDoc(doc(studentDb("u2"), codePath("live")), newCode(times)));
});

for (const [name, overrides] of [
  ["a 21-character token", { token: "a".repeat(21) }],
  ["a 23-character token", { token: "a".repeat(23) }],
  ["a token with a dot", { token: "a".repeat(21) + "." }],
  ["a token that isn't text", { token: 42 }],
  ["no token", { token: undefined }],
  ["the client's time", { rotatedAt: Timestamp.fromMillis(Date.now() - 60_000) }],
  ["an expiry a millisecond late", { expiresAt: undefined, lateExpiry: true }],
  ["no expiry", { expiresAt: undefined }],
  ["a previous token on creation", { previousToken: TOKEN_C }],
  ["a previous issue time on creation", { previousRotatedAt: Timestamp.fromMillis(Date.now()) }],
  ["another field", { eventTitle: "Concert" }],
]) {
  test(`the code can't be created with ${name}`, async () => {
    const times = live();
    await seedOrganizedEvent(times);
    const { lateExpiry, ...fields } = overrides;
    const code = newCode(times, fields);
    if (lateExpiry) code.expiresAt = plusMillis(expiresAtFor(times), 1);
    for (const key of Object.keys(code)) if (code[key] === undefined) delete code[key];
    await assertFails(setDoc(doc(studentDb("u2"), codePath("live")), code));
  });
}

test("the code has one ID: current", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await assertFails(setDoc(doc(studentDb("u2"), "events/live/checkInCode/other"), newCode(times)));
});

test("a rotation 50 s after the last is accepted, 30 s after is refused", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  const recent = await seedCode("live", times, { secondsAgo: 30 });
  await assertFails(setDoc(doc(studentDb("u2"), codePath("live")), rotation(times, recent)));
  const due = await seedCode("live", times, { secondsAgo: 51 });
  await assertSucceeds(setDoc(doc(studentDb("u2"), codePath("live")), rotation(times, due)));
});

for (const [name, write] of [
  ["a previous token that isn't the stored one", (times, stored) =>
    rotation(times, stored, { previousToken: TOKEN_C })],
  ["no previous token", (times, stored) => rotation(times, stored, { previousToken: undefined })],
  // A later issue time would let the replaced token work longer than 2 minutes (#531's review).
  ["a previous issue time that isn't the stored rotatedAt", (times, stored) =>
    rotation(times, stored, { previousRotatedAt: Timestamp.fromMillis(Date.now()) })],
  ["no previous issue time", (times, stored) =>
    rotation(times, stored, { previousRotatedAt: undefined })],
  ["the same token again", (times, stored) => rotation(times, stored, { token: TOKEN_A })],
  ["the client's time", (times, stored) => rotation(times, stored, {
    rotatedAt: Timestamp.fromMillis(Date.now() - 1000),
  })],
  ["another field", (times, stored) => rotation(times, stored, { note: "x" })],
]) {
  test(`a rotation can't have ${name}`, async () => {
    const times = live();
    await seedOrganizedEvent(times);
    const stored = await seedCode("live", times, { secondsAgo: 61 });
    const code = write(times, stored);
    for (const key of Object.keys(code)) if (code[key] === undefined) delete code[key];
    await assertFails(setDoc(doc(studentDb("u2"), codePath("live")), code));
    assert.equal((await storedCode("live")).token, TOKEN_A);
  });
}

test("nobody lists or deletes the code, not even an organizer", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await seedCode("live", times);
  await assertFails(getDocs(collection(studentDb("u2"), "events/live/checkInCode")));
  await assertFails(deleteDoc(doc(studentDb("u2"), codePath("live"))));
});

// -- QR check-ins --

/** u1's QR check-in with [token]. */
function qrCheckIn(times, token) {
  return locationCheckIn("u1", times, { method: "qr", token });
}

test("a student going checks in with the current or the previous token", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await seedCode("live", times, { token: TOKEN_B, previousToken: TOKEN_A, secondsAgo: 20 });
  await assertSucceeds(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_B)));
  await assertSucceeds(checkInAs(studentDb("u3"), "live", "u3",
    locationCheckIn("u3", times, { method: "qr", token: TOKEN_A })));
});

test("a QR check-in with a token that isn't the code's is refused", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await seedCode("live", times, { token: TOKEN_B, previousToken: TOKEN_A });
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_C)));
  assert.equal(await checkInStored("live"), false);
});

test("a QR check-in is refused once the code is 2 minutes old", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await seedCode("live", times, { token: TOKEN_A, secondsAgo: 125 });
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_A)));
  await seedCode("live", times, { token: TOKEN_A, secondsAgo: 100 });
  await assertSucceeds(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_A)));
});

test("each token expires 2 minutes after it was issued, not after the latest rotation", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await seedCode("live", times, {
    token: TOKEN_B, previousToken: TOKEN_A, secondsAgo: 20, previousSecondsAgo: 125,
  });
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_A)));
  await assertSucceeds(checkInAs(studentDb("u3"), "live", "u3",
    locationCheckIn("u3", times, { method: "qr", token: TOKEN_B })));
});

test("a code that expired stays expired after the organizer rotates it again", async () => {
  // #531's review: the screen reopened after a pause rotates a 10-minute-old code at once.
  const times = live();
  await seedOrganizedEvent(times);
  const stale = await seedCode("live", times, { token: TOKEN_A, secondsAgo: 600 });
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_A)));

  await assertSucceeds(setDoc(doc(studentDb("u2"), codePath("live")), rotation(times, stale)));

  await assertFails(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_A)));
  await assertSucceeds(checkInAs(studentDb("u3"), "live", "u3",
    locationCheckIn("u3", times, { method: "qr", token: TOKEN_B })));
});

test("a QR check-in without an entrance code, or without its token, is refused", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await assertFails(checkInAs(studentDb("u1"), "live", "u1", qrCheckIn(times, TOKEN_A)));
  await seedCode("live", times);
  await assertFails(checkInAs(studentDb("u1"), "live", "u1",
    locationCheckIn("u1", times, { method: "qr" })));
});

test("a QR check-in still needs the student to be registered, in the window", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await seedCode("live", times);
  await assertFails(checkInAs(studentDb("u4"), "live", "u4",
    locationCheckIn("u4", times, { method: "qr", token: TOKEN_A })));
  const ended = { startTime: minutesFromNow(-120), endTime: minutesFromNow(-1) };
  await seedCheckInEvent("ended", ended, ["u1"]);
  await seedCode("ended", ended);
  await assertFails(checkInAs(studentDb("u1"), "ended", "u1", qrCheckIn(ended, TOKEN_A)));
});

test("a QR check-in can't carry coordinates or anything else", async () => {
  const times = live();
  await seedOrganizedEvent(times);
  await seedCode("live", times);
  await assertFails(checkInAs(studentDb("u1"), "live", "u1",
    locationCheckIn("u1", times, { method: "qr", token: TOKEN_A, latitude: 46.5 })));
});
