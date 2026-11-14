// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372; never merged).
// PROTOTYPE for #372 (never merged): one test per row of the Architecture Diagram's
// tables (section 6), run against the Firestore emulator with the rules next to this file (the
// document's sketch applied to the repository's rules), or RULES_FILE when set (the mutants).
// The rows that need a decimal number are in rest.rows.test.js.
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
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
  deleteField,
  doc,
  getCountFromServer,
  getDoc,
  getDocs,
  increment,
  runTransaction,
  serverTimestamp,
  setDoc,
  updateDoc,
  writeBatch,
  GeoPoint,
  Timestamp,
} = require("firebase/firestore");
const { cancel, register } = require("./repositoryModel");

const PROJECT_ID = "demo-polysocial";
const FAR_FUTURE = Timestamp.fromDate(new Date("2099-01-01T18:00:00Z"));
const RULES = process.env.RULES_FILE || path.join(__dirname, "firestore.rules");

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(RULES, "utf8") },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    // A legacy event, as the existing suite seeds it: no organizerIds, no capacity, no counter.
    await setDoc(doc(db, "events/e1"), {
      isPrivate: false, createdBy: "u2", allowedUids: ["u2"], startTime: FAR_FUTURE,
    });
    await setDoc(doc(db, "users/u1"), { uid: "u1", displayName: "Student One" });
    await setDoc(doc(db, "users/u2"), { uid: "u2", displayName: "Student Two" });
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

function firestoreAs(email, emailVerified, uid = "u1") {
  return env.authenticatedContext(uid, { email, email_verified: emailVerified }).firestore();
}

function studentDb(uid = "u1") {
  return firestoreAs("student@epfl.ch", true, uid);
}

function associationDb(uid) {
  return firestoreAs("contact@club.org", true, uid);
}

/** An upcoming event as the app writes it, created by [uid], with [overrides]. */
function eventBy(uid, overrides = {}) {
  return {
    title: "Study session",
    description: "",
    category: "STUDY",
    location: new GeoPoint(46.5191, 6.5668),
    startTime: FAR_FUTURE,
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

async function seedEvent(id, data) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `events/${id}`), data);
  });
}

async function seedAttendee(eventId, uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `events/${eventId}/attendees/${uid}`), {
      uid, registeredAt: Timestamp.fromMillis(1_790_000_000_000),
    });
  });
}

/** An event by u2 with [count] registered students (u10, u11, …) and that counter. */
async function seedEventWithCount(id, count, overrides = {}) {
  await seedEvent(id, eventBy("u2", { attendeeCount: count, ...overrides }));
  for (let i = 0; i < count; i++) await seedAttendee(id, `u${10 + i}`);
}

async function seedDeletedAccount(uid) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `deletedAccounts/${uid}`), {});
  });
}

function registration(uid) {
  return { uid, registeredAt: serverTimestamp() };
}

function minutesFromNow(minutes) {
  return Timestamp.fromMillis(Date.now() + minutes * 60_000);
}

/** The stored event's attendeeCount (undefined when missing) and its attendees' IDs, sorted. */
async function stored(eventId) {
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
  assert.deepEqual(await stored(eventId), { count, attendees });
}

/** [uid]'s register through the repository model, with the steps it took. */
async function registerAs(uid, eventId) {
  const steps = [];
  const result = await register(studentDb(uid), uid, eventId, steps);
  return { result, steps: steps.join(" > ") };
}

/** [uid]'s cancel through the repository model, with the steps it took. */
async function cancelAs(uid, eventId) {
  const steps = [];
  const result = await cancel(studentDb(uid), uid, eventId, steps);
  return { result, steps: steps.join(" > ") };
}

// The steps the repository model takes for each outcome.
const WROTE = "write";
const REFUSED = "write: permission-denied";
const REGISTERED_ALREADY = `${REFUSED} > read registration: exists`;
const CLASSIFIED_BY_EVENT = `${REFUSED} > read registration: missing > read event`;
const EVENT_UNREADABLE = `${REFUSED} > read registration: missing > read event: permission-denied`;
const CANCELLED_ALONE = `${REFUSED} > delete registration only`;

/**
 * One batch: optionally creates or deletes [uid]'s registration (under [attendeeEvent], with
 * [attendeeData]), and applies [eventChange] to events/[eventId].
 */
function write(db, eventId, uid, {
  attendee = null, eventChange = null, attendeeEvent = eventId, attendeeData = registration(uid),
}) {
  const batch = writeBatch(db);
  const registrationDoc = doc(db, `events/${attendeeEvent}/attendees/${uid}`);
  if (attendee === "create") batch.set(registrationDoc, attendeeData);
  if (attendee === "delete") batch.delete(registrationDoc);
  if (eventChange) batch.update(doc(db, `events/${eventId}`), eventChange);
  return batch.commit();
}

/** A counted registration, as the app writes it. */
function countedRegistration(db, eventId, uid, attendeeData = registration(uid)) {
  return write(db, eventId, uid, {
    attendee: "create", attendeeData, eventChange: { attendeeCount: increment(1) },
  });
}

// ---- creating an event ----

test("C1 a student creates an event with attendeeCount 0", async () => {
  await assertSucceeds(setDoc(doc(studentDb("u1"), "events/new"), eventBy("u1", { attendeeCount: 0 })));
});

test("C2 an event created without attendeeCount is allowed (an app from before the counter)", async () => {
  await assertSucceeds(setDoc(doc(studentDb("u1"), "events/new"), eventBy("u1")));
});

for (const [name, value] of [
  ["1", 1], ["-1", -1], ["null", null], ['"0"', "0"], ["0.5", 0.5], ["a list", [0]],
]) {
  test(`C3 an event created with attendeeCount ${name} is denied`, async () => {
    await assertFails(setDoc(doc(studentDb("u1"), "events/new"), eventBy("u1", { attendeeCount: value })));
  });
}

test("C4 a verified association creates its event with attendeeCount 0, not 3", async () => {
  const db = associationDb("verifiedClub");
  const event = eventBy("verifiedClub", { isAssociationEvent: true });
  await assertFails(setDoc(doc(db, "events/club1"), { ...event, attendeeCount: 3 }));
  await assertSucceeds(setDoc(doc(db, "events/club1"), { ...event, attendeeCount: 0 }));
});

// ---- an organizer's edits ----

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
  });
}

test("O3 an organizer can't add attendeeCount to an event that has none", async () => {
  await seedEvent("legacy", eventBy("u2"));
  await assertFails(updateDoc(doc(studentDb("u2"), "events/legacy"), { attendeeCount: 0 }));
  await assertFails(updateDoc(doc(studentDb("u2"), "events/legacy"), { attendeeCount: 5 }));
});

test("O4 an organizer overwriting the event without attendeeCount is denied; with the same value, allowed", async () => {
  await seedEventWithCount("managed", 3, { capacity: 10 });
  const db = studentDb("u2");
  await assertFails(setDoc(doc(db, "events/managed"), eventBy("u2", { title: "Rewritten" })));
  await assertSucceeds(setDoc(doc(db, "events/managed"), eventBy("u2", { title: "Rewritten", attendeeCount: 3 })));
});

test("O5 an organizer may lower the capacity below the count: nobody new registers, students still cancel", async () => {
  await seedEventWithCount("managed", 3, { capacity: 10 });
  await assertSucceeds(updateDoc(doc(studentDb("u2"), "events/managed"), { capacity: 2 }));
  await assertFails(countedRegistration(studentDb("u1"), "managed", "u1"));
  assert.deepEqual(await registerAs("u1", "managed"), { result: "Full", steps: `${CLASSIFIED_BY_EVENT}: full` });
  assert.deepEqual(await cancelAs("u10", "managed"), { result: "Cancelled", steps: WROTE });
  await expectStored("managed", 2, ["u11", "u12"]);
  // Still full at 2 of 2.
  await assertFails(countedRegistration(studentDb("u1"), "managed", "u1"));
});

test("O6 a verified association can't change its own event's attendeeCount", async () => {
  await seedEvent("clubEvent", eventBy("verifiedClub", { isAssociationEvent: true, attendeeCount: 2 }));
  const db = associationDb("verifiedClub");
  await assertFails(updateDoc(doc(db, "events/clubEvent"), { attendeeCount: 3 }));
  await assertFails(updateDoc(doc(db, "events/clubEvent"), { attendeeCount: 1 }));
  await assertSucceeds(updateDoc(doc(db, "events/clubEvent"), { title: "Jam" }));
});

test("O7 an organizer who is a student registers for their own event like any student", async () => {
  await seedEventWithCount("managed", 1, { capacity: 2 });
  assert.deepEqual(await registerAs("u2", "managed"), { result: "Registered", steps: WROTE });
  await expectStored("managed", 2, ["u10", "u2"]);
  // …but can't give themselves a second place, or free one, as an organizer.
  await assertFails(updateDoc(doc(studentDb("u2"), "events/managed"), { attendeeCount: 1 }));
});

test("O8 an organizer can't register and edit the event in one write", async () => {
  await seedEventWithCount("managed", 1, { capacity: 5 });
  await assertFails(write(studentDb("u2"), "managed", "u2", {
    attendee: "create", eventChange: { attendeeCount: increment(1), title: "Renamed" },
  }));
  await expectStored("managed", 1, ["u10"]);
});

test("O9 an organizer deletes an event that has registrations: the counter goes with it", async () => {
  await seedEventWithCount("managed", 3);
  await assertSucceeds(deleteDoc(doc(studentDb("u2"), "events/managed")));
  await expectStored("managed", "no event", ["u10", "u11", "u12"]);
});

// ---- registering ----

test("R1 a student registers as the app will: a write-only transaction, registration and increment(1)", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  assert.deepEqual(await registerAs("u1", "open"), { result: "Registered", steps: WROTE });
  await expectStored("open", 4, ["u1", "u10", "u11", "u12"]);
});

test("R2 registering for an event without a counter (and without organizerIds) starts it at 1", async () => {
  assert.deepEqual(await registerAs("u1", "e1"), { result: "Registered", steps: WROTE });
  await expectStored("e1", 1, ["u1"]);
});

test("R3 registering at capacity - 1 is allowed, and at capacity denied: the repository answers Full", async () => {
  await seedEventWithCount("small", 1, { capacity: 2 });
  await assertSucceeds(countedRegistration(studentDb("u1"), "small", "u1"));
  await expectStored("small", 2, ["u1", "u10"]);
  await assertFails(countedRegistration(studentDb("u3"), "small", "u3"));
  assert.deepEqual(await registerAs("u3", "small"), { result: "Full", steps: `${CLASSIFIED_BY_EVENT}: full` });
  await expectStored("small", 2, ["u1", "u10"]);
});

for (const [name, overrides] of [
  ["a null capacity", { capacity: null }],
  ["no capacity field", {}],
]) {
  test(`R4 an event with ${name} takes registrations whatever the count, and still counts them`, async () => {
    const event = eventBy("u2", { attendeeCount: 1000, ...overrides });
    if (!("capacity" in overrides)) delete event.capacity;
    await seedEvent("unlimited", event);
    assert.deepEqual(await registerAs("u1", "unlimited"), { result: "Registered", steps: WROTE });
    await expectStored("unlimited", 1001, ["u1"]);
  });
}

for (const [name, capacity] of [['a string ("2")', "2"], ["a list", [2]], ["a boolean", true]]) {
  test(`R5 a capacity that is ${name} is no limit`, async () => {
    await seedEvent("odd", eventBy("u2", { attendeeCount: 5, capacity }));
    await assertSucceeds(countedRegistration(studentDb("u1"), "odd", "u1"));
    await expectStored("odd", 6, ["u1"]);
  });
}

test("R6 a decimal capacity (2.5) limits like a number: 2 fits, 3 doesn't", async () => {
  await seedEvent("odd", eventBy("u2", { attendeeCount: 1, capacity: 2.5 }));
  await assertSucceeds(countedRegistration(studentDb("u1"), "odd", "u1"));
  await assertFails(countedRegistration(studentDb("u3"), "odd", "u3"));
  assert.equal((await registerAs("u3", "odd")).result, "Full");
  await expectStored("odd", 2, ["u1"]);
});

for (const capacity of [0, -3, 1]) {
  test(`R7 a capacity of ${capacity} (the form asks for at least 2) is still a limit`, async () => {
    await seedEvent("odd", eventBy("u2", { attendeeCount: 1, capacity }));
    await seedAttendee("odd", "u10");
    await assertFails(countedRegistration(studentDb("u1"), "odd", "u1"));
    assert.equal((await registerAs("u1", "odd")).result, "Full");
    assert.deepEqual(await cancelAs("u10", "odd"), { result: "Cancelled", steps: WROTE });
    await expectStored("odd", 0, []);
  });
}

for (const [name, change] of [
  ["raises the counter by 2", { attendeeCount: 5 }],
  ["raises the counter by 2 with increment(2)", { attendeeCount: increment(2) }],
  ["leaves the counter unchanged", { attendeeCount: 3 }],
  ["lowers the counter", { attendeeCount: 2 }],
  ["sets the counter to a string", { attendeeCount: "4" }],
  ["removes the counter", { attendeeCount: deleteField() }],
  ["also changes the title", { attendeeCount: increment(1), title: "Hijacked" }],
  ["also raises the capacity", { attendeeCount: increment(1), capacity: 99 }],
  ["also adds themselves to allowedUids", { attendeeCount: increment(1), allowedUids: ["u2", "u1"] }],
  ["also makes themselves an organizer", { attendeeCount: increment(1), organizerIds: ["u2", "u1"], allowedUids: ["u2", "u1"] }],
]) {
  test(`${name.startsWith("also") ? "R9" : "R8"} a registration whose event write ${name} is denied`, async () => {
    await seedEventWithCount("open", 3, { capacity: 10 });
    await assertFails(write(studentDb("u1"), "open", "u1", { attendee: "create", eventChange: change }));
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

test("R13 already registered: the write is denied, the repository reads the registration back", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  assert.deepEqual(await registerAs("u1", "open"), { result: "Registered", steps: WROTE });
  const first = (await stored("open"));
  assert.deepEqual(await registerAs("u1", "open"), { result: "Registered", steps: REGISTERED_ALREADY });
  assert.deepEqual(await stored("open"), first);
  await assertFails(countedRegistration(studentDb("u1"), "open", "u1"));
  await assertFails(updateDoc(doc(studentDb("u1"), "events/open"), { attendeeCount: increment(1) }));
  await expectStored("open", 4, ["u1", "u10", "u11", "u12"]);
});

test("R13b already registered on a full event is still Registered, not Full", async () => {
  await seedEventWithCount("small", 2, { capacity: 2 });
  assert.deepEqual(await registerAs("u10", "small"), { result: "Registered", steps: REGISTERED_ALREADY });
  await expectStored("small", 2, ["u10", "u11"]);
});

test("R14 two students at once for the last place: one gets it, the other is denied and answered Full", async () => {
  await seedEventWithCount("last", 1, { capacity: 2 });
  const outcomes = await Promise.all([registerAs("u1", "last"), registerAs("u3", "last")]);
  assert.deepEqual(outcomes.map((outcome) => outcome.result).sort(), ["Full", "Registered"]);
  const loser = outcomes.find((outcome) => outcome.result === "Full");
  assert.equal(loser.steps, `${CLASSIFIED_BY_EVENT}: full`);
  const after = await stored("last");
  assert.equal(after.count, 2);
  assert.equal(after.attendees.length, 2);
});

test("R14b a count that became stale can't take the last place either", async () => {
  await seedEventWithCount("last", 1, { capacity: 2 });
  await assertSucceeds(countedRegistration(studentDb("u3"), "last", "u3"));
  // u1 read 1 before u3 registered and writes 1 + 1.
  await assertFails(write(studentDb("u1"), "last", "u1", { attendee: "create", eventChange: { attendeeCount: 2 } }));
  await assertFails(countedRegistration(studentDb("u1"), "last", "u1"));
  await expectStored("last", 2, ["u10", "u3"]);
});

test("R15 two students at once with places left both register; a count that became stale is denied", async () => {
  await seedEventWithCount("roomy", 1, { capacity: 20 });
  const outcomes = await Promise.all([registerAs("u1", "roomy"), registerAs("u3", "roomy")]);
  assert.deepEqual(outcomes, [{ result: "Registered", steps: WROTE }, { result: "Registered", steps: WROTE }]);
  await expectStored("roomy", 3, ["u1", "u10", "u3"]);
  // u4 read 1 before the two registered and writes 1 + 1.
  await assertFails(write(studentDb("u4"), "roomy", "u4", { attendee: "create", eventChange: { attendeeCount: 2 } }));
  await expectStored("roomy", 3, ["u1", "u10", "u3"]);
});

test("R16 a counted registration that writes the stored count plus one, instead of increment(1), is accepted too", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  await assertSucceeds(write(studentDb("u1"), "open", "u1", { attendee: "create", eventChange: { attendeeCount: 4 } }));
  await expectStored("open", 4, ["u1", "u10", "u11", "u12"]);
});

test("R17 a private event: an allowed student registers, another student can't and is answered NotFound", async () => {
  await seedEvent("private", eventBy("u2", { isPrivate: true, allowedUids: ["u2", "u3"], capacity: 5, attendeeCount: 0 }));
  assert.deepEqual(await registerAs("u3", "private"), { result: "Registered", steps: WROTE });
  await assertFails(countedRegistration(studentDb("u1"), "private", "u1"));
  await assertFails(updateDoc(doc(studentDb("u1"), "events/private"), { attendeeCount: increment(1) }));
  assert.deepEqual(await registerAs("u1", "private"), { result: "NotFound", steps: EVENT_UNREADABLE });
  await expectStored("private", 1, ["u3"]);
});

test("R18 an ended event takes no registration, even with the counter: UnexpectedError", async () => {
  await seedEvent("ended", eventBy("u2", { startTime: minutesFromNow(-120), endTime: minutesFromNow(-1), attendeeCount: 0 }));
  await assertFails(countedRegistration(studentDb("u1"), "ended", "u1"));
  assert.deepEqual(await registerAs("u1", "ended"), { result: "UnexpectedError", steps: `${CLASSIFIED_BY_EVENT}: a place left` });
  await expectStored("ended", 0, []);
});

test("R19 a deleted account can't register with its still-valid token: UnexpectedError", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  await seedDeletedAccount("u1");
  await assertFails(countedRegistration(studentDb("u1"), "open", "u1"));
  assert.deepEqual(await registerAs("u1", "open"), { result: "UnexpectedError", steps: `${CLASSIFIED_BY_EVENT}: a place left` });
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

for (const [name, uid, db] of [
  ["a verified association", "verifiedClub", () => associationDb("verifiedClub")],
  ["an unverified association", "club", () => associationDb("club")],
  ["an @epfl.ch association", "epflClub", () => firestoreAs("club@epfl.ch", true, "epflClub")],
  ["an unverified @epfl.ch user", "u1", () => firestoreAs("student@epfl.ch", false, "u1")],
  ["a verified non-EPFL user without a profile", "u7", () => firestoreAs("someone@gmail.com", true, "u7")],
  ["a signed-out user", "u1", () => env.unauthenticatedContext().firestore()],
]) {
  test(`R20 ${name} can't change attendeeCount, with or without a registration`, async () => {
    await seedEventWithCount("open", 3, { capacity: 10 });
    await assertFails(countedRegistration(db(), "open", uid));
    await assertFails(updateDoc(doc(db(), "events/open"), { attendeeCount: increment(1) }));
    await assertFails(updateDoc(doc(db(), "events/open"), { attendeeCount: increment(-1) }));
    // Even with a registration seeded under their UID.
    await seedAttendee("open", uid);
    await assertFails(write(db(), "open", uid, { attendee: "delete", eventChange: { attendeeCount: increment(-1) } }));
    await expectStored("open", 3, ["u10", "u11", "u12", uid].sort());
  });
}

test("R21 registering for an event that doesn't exist is denied: NotFound", async () => {
  const db = studentDb("u1");
  const batch = writeBatch(db);
  batch.set(doc(db, "events/missing/attendees/u1"), registration("u1"));
  batch.set(doc(db, "events/missing"), { attendeeCount: 1 }, { merge: true });
  await assertFails(batch.commit());
  await assertFails(countedRegistration(db, "missing", "u1"));
  assert.deepEqual(await registerAs("u1", "missing"), { result: "NotFound", steps: EVENT_UNREADABLE });
  await expectStored("missing", "no event", []);
});

test("R22 a registration for one event can't raise another event's counter", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  await seedEventWithCount("other", 0, { capacity: 10 });
  // The registration is under "other", the counter change on "open".
  await assertFails(write(studentDb("u1"), "open", "u1", {
    attendee: "create", attendeeEvent: "other", eventChange: { attendeeCount: increment(1) },
  }));
  await expectStored("open", 3, ["u10", "u11", "u12"]);
  await expectStored("other", 0, []);
});

test("R23 a student registered before the counter existed is already registered: nothing is written", async () => {
  await seedEvent("legacy", eventBy("u2", { capacity: 2 }));
  await seedAttendee("legacy", "u1");
  assert.deepEqual(await registerAs("u1", "legacy"), { result: "Registered", steps: REGISTERED_ALREADY });
  await expectStored("legacy", undefined, ["u1"]);
});

// R24 is in rest.rows.test.js (a decimal counter).

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

for (const [id, name, data] of [
  ["R26", "an extra field", { ...registration("u1"), note: "x" }],
  ["R27", "a registeredAt the client chose", { uid: "u1", registeredAt: Timestamp.fromMillis(0) }],
  ["R28", "another student's UID in uid", registration("u9")],
  ["R28", "no uid", { registeredAt: serverTimestamp() }],
  ["R27", "no registeredAt", { uid: "u1" }],
]) {
  test(`${id} a counted registration with ${name} is denied`, async () => {
    await seedEventWithCount("open", 3, { capacity: 10 });
    await assertFails(countedRegistration(studentDb("u1"), "open", "u1", data));
    await expectStored("open", 3, ["u10", "u11", "u12"]);
    // The same write with the two fields the app sends is accepted.
    await assertSucceeds(countedRegistration(studentDb("u1"), "open", "u1"));
  });
}

// ---- cancelling ----

test("X1 a student cancels as the app will: a write-only transaction, delete and increment(-1)", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  assert.deepEqual(await cancelAs("u10", "open"), { result: "Cancelled", steps: WROTE });
  await expectStored("open", 2, ["u11", "u12"]);
  // The place is free again.
  assert.deepEqual(await registerAs("u1", "open"), { result: "Registered", steps: WROTE });
  await expectStored("open", 3, ["u1", "u11", "u12"]);
  // Writing the stored count minus one is accepted too.
  await assertSucceeds(write(studentDb("u11"), "open", "u11", { attendee: "delete", eventChange: { attendeeCount: 2 } }));
  await expectStored("open", 2, ["u1", "u12"]);
});

test("X2 cancelling after the event ended still lowers the counter", async () => {
  await seedEventWithCount("ended", 2, { startTime: minutesFromNow(-120), endTime: minutesFromNow(-1) });
  assert.deepEqual(await cancelAs("u10", "ended"), { result: "Cancelled", steps: WROTE });
  await expectStored("ended", 1, ["u11"]);
});

test("X3 deleting a counted registration without lowering the counter is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  await assertFails(deleteDoc(doc(studentDb("u10"), "events/open/attendees/u10")));
  await assertFails(write(studentDb("u10"), "open", "u10", { attendee: "delete", eventChange: { attendeeCount: 3 } }));
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

test("X3 the only counted registration (counter 1) deleted without lowering the counter is denied", async () => {
  // The boundary of the delete rule's `countBefore() == 0`: at 1 the place is still counted.
  await seedEventWithCount("one", 1, { capacity: 3 });
  await assertFails(deleteDoc(doc(studentDb("u10"), "events/one/attendees/u10")));
  await expectStored("one", 1, ["u10"]);
  // Counted, the same cancellation is accepted and leaves 0.
  await assertSucceeds(write(studentDb("u10"), "one", "u10", { attendee: "delete", eventChange: { attendeeCount: increment(-1) } }));
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
    await assertFails(write(studentDb("u10"), "open", "u10", { attendee: "delete", eventChange: change }));
    await expectStored("open", 3, ["u10", "u11", "u12"]);
  });
}

test("X5 lowering the counter without deleting the caller's registration is denied", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  // Registered, but keeps the registration.
  await assertFails(updateDoc(doc(studentDb("u10"), "events/open"), { attendeeCount: 2 }));
  await assertFails(updateDoc(doc(studentDb("u10"), "events/open"), { attendeeCount: increment(-1) }));
  // Not registered at all (deleting a missing registration frees no place).
  await assertFails(updateDoc(doc(studentDb("u1"), "events/open"), { attendeeCount: increment(-1) }));
  await assertFails(write(studentDb("u1"), "open", "u1", { attendee: "delete", eventChange: { attendeeCount: increment(-1) } }));
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

for (const [name, event] of [
  ["missing", () => eventBy("u2")],
  ["0", () => eventBy("u2", { attendeeCount: 0 })],
]) {
  test(`X6 with the counter ${name}, a registration from before the counter is deleted alone, never below 0`, async () => {
    await seedEvent("legacy", event());
    await seedAttendee("legacy", "u1");
    await assertFails(write(studentDb("u1"), "legacy", "u1", { attendee: "delete", eventChange: { attendeeCount: -1 } }));
    await assertFails(write(studentDb("u1"), "legacy", "u1", { attendee: "delete", eventChange: { attendeeCount: increment(-1) } }));
    // Nor may the write that deletes it set the counter to anything else.
    await assertFails(write(studentDb("u1"), "legacy", "u1", { attendee: "delete", eventChange: { attendeeCount: 5 } }));
    await assertFails(write(studentDb("u1"), "legacy", "u1", { attendee: "delete", eventChange: { attendeeCount: increment(1) } }));
    assert.deepEqual(await cancelAs("u1", "legacy"), { result: "Cancelled", steps: CANCELLED_ALONE });
    const after = await stored("legacy");
    assert.deepEqual(after.attendees, []);
    assert.ok(after.count === undefined || after.count === 0);
  });
}

test("X7 a registration for a deleted event is deleted alone", async () => {
  await seedAttendee("gone", "u1");
  await assertFails(write(studentDb("u1"), "gone", "u1", { attendee: "delete", eventChange: { attendeeCount: increment(-1) } }));
  assert.deepEqual(await cancelAs("u1", "gone"), { result: "Cancelled", steps: CANCELLED_ALONE });
  await expectStored("gone", "no event", []);
});

test("X8 a student who can no longer read the event deletes the registration alone; the counter stays", async () => {
  await seedEvent("closed", eventBy("u2", { isPrivate: true, allowedUids: ["u2"], attendeeCount: 1 }));
  await seedAttendee("closed", "u1");
  const db = studentDb("u1");
  // They can't write an event they can't read, even only to lower the counter.
  await assertFails(write(db, "closed", "u1", { attendee: "delete", eventChange: { attendeeCount: 0 } }));
  await assertFails(write(db, "closed", "u1", { attendee: "delete", eventChange: { attendeeCount: increment(-1) } }));
  // So the repository falls back to deleting the registration alone.
  assert.deepEqual(await cancelAs("u1", "closed"), { result: "Cancelled", steps: CANCELLED_ALONE });
  await expectStored("closed", 1, []);
});

test("X9 cancelling without a registration is still allowed on a counted event, and counts nothing", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  await assertSucceeds(deleteDoc(doc(studentDb("u1"), "events/open/attendees/u1")));
  assert.deepEqual(await cancelAs("u1", "open"), { result: "Cancelled", steps: CANCELLED_ALONE });
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

test("X10 a deleted account can still cancel with its still-valid token", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  await seedDeletedAccount("u10");
  assert.deepEqual(await cancelAs("u10", "open"), { result: "Cancelled", steps: WROTE });
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
  const outcomes = await Promise.all([cancelAs("u10", "open"), cancelAs("u11", "open")]);
  assert.deepEqual(outcomes, [{ result: "Cancelled", steps: WROTE }, { result: "Cancelled", steps: WROTE }]);
  await expectStored("open", 1, ["u12"]);
  // u12 read 3 before the two cancelled and writes 3 - 1.
  await assertFails(write(studentDb("u12"), "open", "u12", { attendee: "delete", eventChange: { attendeeCount: 2 } }));
  await expectStored("open", 1, ["u12"]);
});

test("X13 a student can't free a place by registering and cancelling in one write", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  const db = studentDb("u10");
  // Deletes and recreates their registration in one batch while lowering the counter.
  const batch = writeBatch(db);
  batch.delete(doc(db, "events/open/attendees/u10"));
  batch.set(doc(db, "events/open/attendees/u10"), registration("u10"));
  batch.update(doc(db, "events/open"), { attendeeCount: increment(-1) });
  await assertFails(batch.commit());
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

// ---- organizers' reads, unchanged ----

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

// ---- what the repository answers (decision 21, "The app's transactions") ----

test("P1 register, a place left: one write, Registered", async () => {
  await seedEventWithCount("open", 3, { capacity: 10 });
  assert.deepEqual(await registerAs("u1", "open"), { result: "Registered", steps: WROTE });
  await expectStored("open", 4, ["u1", "u10", "u11", "u12"]);
});

for (const [name, seed, uid] of [
  ["a second tap", () => seedEventWithCount("open", 3, { capacity: 10 }), "u10"],
  ["on a full event", () => seedEventWithCount("open", 3, { capacity: 3 }), "u10"],
  ["a registration from before the counter", async () => {
    await seedEvent("open", eventBy("u2", { capacity: 2 }));
    await seedAttendee("open", "u10");
  }, "u10"],
]) {
  test(`P2 register, already registered (${name}): the write is refused, the registration read back, Registered`, async () => {
    await seed();
    const before = await stored("open");
    assert.deepEqual(await registerAs(uid, "open"), { result: "Registered", steps: REGISTERED_ALREADY });
    assert.deepEqual(await stored("open"), before);
  });
}

for (const [name, overrides] of [
  ["at capacity", { capacity: 3 }],
  ["capacity lowered below the count", { capacity: 2 }],
  ["a decimal capacity (3.5)", { capacity: 3.5 }],
  ["a capacity of 0", { capacity: 0 }],
]) {
  test(`P3 register, event full (${name}): refused, no registration, the event read back, Full`, async () => {
    await seedEventWithCount("open", 3, overrides);
    assert.deepEqual(await registerAs("u1", "open"), { result: "Full", steps: `${CLASSIFIED_BY_EVENT}: full` });
    await expectStored("open", 3, ["u10", "u11", "u12"]);
  });
}

test("P4 register, event missing or unreadable: refused, the event's read denied, NotFound", async () => {
  await seedEvent("closed", eventBy("u2", { isPrivate: true, attendeeCount: 0 }));
  assert.deepEqual(await registerAs("u1", "missing"), { result: "NotFound", steps: EVENT_UNREADABLE });
  assert.deepEqual(await registerAs("u1", "closed"), { result: "NotFound", steps: EVENT_UNREADABLE });
  await expectStored("closed", 0, []);
});

test("P5 register, ended event or deleted account: refused, a place left, UnexpectedError", async () => {
  await seedEvent("ended", eventBy("u2", { startTime: minutesFromNow(-120), endTime: minutesFromNow(-1), attendeeCount: 0 }));
  await seedEventWithCount("open", 3, { capacity: 10 });
  await seedDeletedAccount("u5");
  const inconclusive = { result: "UnexpectedError", steps: `${CLASSIFIED_BY_EVENT}: a place left` };
  assert.deepEqual(await registerAs("u1", "ended"), inconclusive);
  assert.deepEqual(await registerAs("u5", "open"), inconclusive);
  await expectStored("ended", 0, []);
  await expectStored("open", 3, ["u10", "u11", "u12"]);
});

test("P6 cancel a counted registration: one write, Cancelled", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  assert.deepEqual(await cancelAs("u10", "open"), { result: "Cancelled", steps: WROTE });
  await expectStored("open", 2, ["u11", "u12"]);
});

for (const [name, seed, expected] of [
  ["no registration", () => seedEventWithCount("open", 3), { count: 3, attendees: ["u10", "u11", "u12"] }],
  ["event deleted", () => seedAttendee("open", "u1"), { count: "no event", attendees: [] }],
  ["event no longer readable", async () => {
    await seedEvent("open", eventBy("u2", { isPrivate: true, attendeeCount: 1 }));
    await seedAttendee("open", "u1");
  }, { count: 1, attendees: [] }],
  ["counter at 0", async () => {
    await seedEvent("open", eventBy("u2", { attendeeCount: 0 }));
    await seedAttendee("open", "u1");
  }, { count: 0, attendees: [] }],
  ["counter absent", async () => {
    await seedEvent("open", eventBy("u2"));
    await seedAttendee("open", "u1");
  }, { count: undefined, attendees: [] }],
]) {
  test(`P7 cancel, ${name}: the counted cancellation is refused, the registration-only delete accepted, Cancelled`, async () => {
    await seed();
    assert.deepEqual(await cancelAs("u1", "open"), { result: "Cancelled", steps: CANCELLED_ALONE });
    assert.deepEqual(await stored("open"), expected);
  });
}

test("P8 cancel by an account the rules refuse: both writes refused, UnexpectedError, nothing changes", async () => {
  await seedEventWithCount("open", 3, { capacity: 3 });
  await seedAttendee("open", "verifiedClub");
  const steps = [];
  const result = await cancel(associationDb("verifiedClub"), "verifiedClub", "open", steps);
  assert.deepEqual({ result, steps: steps.join(" > ") }, {
    result: "UnexpectedError", steps: `${REFUSED} > delete registration only: permission-denied`,
  });
  await expectStored("open", 3, ["u10", "u11", "u12", "verifiedClub"]);
});

test("NOTE a read-only transaction's commit verifies what it read: a change to the event in between fails it", async () => {
  // The shape of the model's classification (registration, then event, no write, tried once),
  // with an organizer's edit landing between the event's read and the commit: UnexpectedError.
  await seedEventWithCount("open", 3, { capacity: 3 });
  const db = studentDb("u1");
  const outcome = await runTransaction(db, async (transaction) => {
    await transaction.get(doc(db, "events/open/attendees/u1"));
    await transaction.get(doc(db, "events/open"));
    await env.withSecurityRulesDisabled((context) =>
      updateDoc(doc(context.firestore(), "events/open"), { title: "Moved" }));
    return "Full";
  }, { maxAttempts: 1 }).then((result) => result, (error) => `Error(${error.code})`);
  assert.match(outcome, /^Error\((failed-precondition|aborted)\)$/);
});

test("P9 two students at once for the last place: one Registered, one Full", async () => {
  await seedEventWithCount("open", 2, { capacity: 3 });
  const outcomes = await Promise.all([registerAs("u1", "open"), registerAs("u3", "open")]);
  assert.deepEqual(outcomes.map((outcome) => outcome.result).sort(), ["Full", "Registered"]);
  const after = await stored("open");
  assert.equal(after.count, 3);
  assert.equal(after.attendees.length, 3);
});
