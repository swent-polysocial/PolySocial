// Contributors: Claude (rules tests for #33; also firebase/package.json, which can't hold a comment); Claude Opus 5.5 (testing agent: added edge-case, operation and path coverage tests).
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
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
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
  });
});

after(async () => {
  await env.cleanup();
});

/** Firestore as a signed-in user with the given email claims. */
function firestoreAs(email, emailVerified) {
  return env
    .authenticatedContext("u1", { email, email_verified: emailVerified })
    .firestore();
}

/**
 * Every kind of access the catch-all rule governs, on a top-level collection and on a
 * subcollection. Each entry is run on its own so a test names the access that broke.
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

test("a verified @epfl.ch user can read and write", async () => {
  const db = firestoreAs("student@epfl.ch", true);

  await assertSucceeds(getDoc(doc(db, "events/e1")));
  await assertSucceeds(setDoc(doc(db, "events/e2"), { title: "New" }));
});

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

for (const [name, access] of Object.entries(ACCESSES)) {
  test(`a verified @epfl.ch user is allowed: ${name}`, async () => {
    await assertSucceeds(access(firestoreAs("student@epfl.ch", true)));
  });
}

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
