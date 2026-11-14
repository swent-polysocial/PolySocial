// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372; never merged).
// PROTOTYPE for #372 (never merged): the rows that need a number sent as a decimal
// (C5, O10, R24). The JS SDK always sends a whole number as an integer, so these go through the
// emulator's REST API, with the unsigned token the emulator accepts. Rules next to this file, or
// RULES_FILE when set.
const { after, before, beforeEach, test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const { collection, doc, getDocs, setDoc, Timestamp, GeoPoint } = require("firebase/firestore");

const PROJECT_ID = "demo-polysocial";
const HOST = process.env.FIRESTORE_EMULATOR_HOST;
const ROOT = `projects/${PROJECT_ID}/databases/(default)/documents`;
const RULES = process.env.RULES_FILE || path.join(__dirname, "firestore.rules");
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(RULES, "utf8") },
  });
});
after(async () => { await env.cleanup(); });

async function admin(run) {
  let result;
  await env.withSecurityRulesDisabled(async (context) => { result = await run(context.firestore()); });
  return result;
}

/** The event's fields as REST values, created by [uid], with [overrides] (REST values too). */
function eventFields(uid, overrides = {}) {
  const str = (value) => ({ stringValue: value });
  return {
    title: str("Study session"), description: str(""), category: str("STUDY"),
    location: { geoPointValue: { latitude: 46.5, longitude: 6.5 } },
    startTime: { timestampValue: "2099-01-01T18:00:00Z" }, endTime: { nullValue: null },
    capacity: { integerValue: "10" }, isPrivate: { booleanValue: false }, createdBy: str(uid),
    organizerIds: { arrayValue: { values: [str(uid)] } },
    allowedUids: { arrayValue: { values: [str(uid)] } },
    isAssociationEvent: { booleanValue: false }, ...overrides,
  };
}

beforeEach(async () => {
  await env.clearFirestore();
  await admin(async (db) => {
    await setDoc(doc(db, "events/open"), {
      title: "Study session", description: "", category: "STUDY", location: new GeoPoint(46.5, 6.5),
      startTime: Timestamp.fromDate(new Date("2099-01-01T18:00:00Z")), endTime: null, capacity: 10,
      isPrivate: false, createdBy: "u2", organizerIds: ["u2"], allowedUids: ["u2"],
      isAssociationEvent: false, attendeeCount: 3,
    });
    for (const uid of ["u10", "u11", "u12"]) {
      await setDoc(doc(db, `events/open/attendees/${uid}`), { uid, registeredAt: Timestamp.fromMillis(0) });
    }
  });
});

function token(uid) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${PROJECT_ID}`, aud: PROJECT_ID, sub: uid, user_id: uid,
    email: "student@epfl.ch", email_verified: true, iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: "password", identities: {} },
  })}.`;
}

/** Commits [writes] as the student [uid]; true if the rules allowed it. */
async function allowed(uid, writes) {
  const response = await fetch(`http://${HOST}/v1/${ROOT}:commit`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token(uid)}`, "Content-Type": "application/json" },
    body: JSON.stringify({ writes }),
  });
  await response.text();
  if (response.status !== 200 && response.status !== 403) {
    throw new Error(`Unexpected HTTP ${response.status} from the emulator`);
  }
  return response.status === 200;
}

/** A field of a stored document as the REST API returns it, with its type. */
async function rawField(pathName, field) {
  const response = await fetch(`http://${HOST}/v1/${ROOT}/${pathName}`, {
    headers: { Authorization: "Bearer owner" },
  });
  return ((await response.json()).fields || {})[field];
}

async function registrations(eventId) {
  return admin(async (db) =>
    (await getDocs(collection(db, `events/${eventId}/attendees`))).docs.map((d) => d.id).sort());
}

const int = (value) => ({ integerValue: String(value) });
const dbl = (value) => ({ doubleValue: value });
const createRegistration = (eventId, uid) => ({
  update: { name: `${ROOT}/events/${eventId}/attendees/${uid}`, fields: { uid: { stringValue: uid } } },
  updateTransforms: [{ fieldPath: "registeredAt", setToServerValue: "REQUEST_TIME" }],
  currentDocument: { exists: false },
});
const setCount = (eventId, value) => ({
  update: { name: `${ROOT}/events/${eventId}`, fields: { attendeeCount: value } },
  updateMask: { fieldPaths: ["attendeeCount"] },
  currentDocument: { exists: true },
});
const incrementCount = (eventId, value) => ({
  update: { name: `${ROOT}/events/${eventId}`, fields: {} },
  updateMask: { fieldPaths: [] },
  updateTransforms: [{ fieldPath: "attendeeCount", increment: value }],
  currentDocument: { exists: true },
});
const setEvent = (eventId, fields, precondition) => ({
  update: { name: `${ROOT}/events/${eventId}`, fields }, currentDocument: precondition,
});

test("C5 an event created with attendeeCount 0.0 is denied; with the integer 0, allowed", async () => {
  const create = (id, count) =>
    allowed("u1", [setEvent(id, eventFields("u1", { attendeeCount: count }), { exists: false })]);
  assert.equal(await create("decimal", dbl(0.0)), false);
  assert.equal(await create("whole", int(0)), true);
  assert.deepEqual(await rawField("events/whole", "attendeeCount"), { integerValue: "0" });
});

test("O10 an organizer overwriting the event with attendeeCount 3.0 for 3 is denied; with 3, allowed", async () => {
  const overwrite = (count) =>
    allowed("u2", [setEvent("open", eventFields("u2", { title: { stringValue: "Rewritten" }, attendeeCount: count }), { exists: true })]);
  assert.equal(await overwrite(dbl(3.0)), false);
  assert.deepEqual(await rawField("events/open", "attendeeCount"), { integerValue: "3" });
  assert.equal(await overwrite(int(3)), true);
  assert.deepEqual(await rawField("events/open", "attendeeCount"), { integerValue: "3" });
});

test("R24 a counted registration whose counter is a decimal (4.0, or increment(1.0)) is denied", async () => {
  assert.equal(await allowed("u1", [createRegistration("open", "u1"), setCount("open", dbl(4.0))]), false);
  assert.equal(await allowed("u1", [createRegistration("open", "u1"), incrementCount("open", dbl(1.0))]), false);
  assert.deepEqual(await rawField("events/open", "attendeeCount"), { integerValue: "3" });
  assert.deepEqual(await registrations("open"), ["u10", "u11", "u12"]);
  // The same commits with integers are accepted.
  assert.equal(await allowed("u1", [createRegistration("open", "u1"), setCount("open", int(4))]), true);
  assert.equal(await allowed("u3", [createRegistration("open", "u3"), incrementCount("open", int(1))]), true);
  assert.deepEqual(await rawField("events/open", "attendeeCount"), { integerValue: "5" });
});

test("R24b a counted cancellation whose counter is a decimal (2.0, or increment(-1.0)) is denied", async () => {
  const remove = { delete: `${ROOT}/events/open/attendees/u10` };
  assert.equal(await allowed("u10", [remove, setCount("open", dbl(2.0))]), false);
  assert.equal(await allowed("u10", [remove, incrementCount("open", dbl(-1.0))]), false);
  assert.deepEqual(await rawField("events/open", "attendeeCount"), { integerValue: "3" });
  assert.equal(await allowed("u10", [remove, incrementCount("open", int(-1))]), true);
  assert.deepEqual(await rawField("events/open", "attendeeCount"), { integerValue: "2" });
});

// Not a row, but what the document says about `capacity`: numbers compare by value, so a student's
// counted registration may send the stored capacity back as 10.0. The rules take any number as the
// capacity and the app reads it as a Number, so nothing depends on its type.
test("NOTE a counted registration can send the stored capacity back as 10.0 (equal by value)", async () => {
  const resend = setEvent("open", eventFields("u2", { capacity: dbl(10.0), attendeeCount: int(4) }), { exists: true });
  assert.equal(await allowed("u1", [createRegistration("open", "u1"), resend]), true);
  assert.deepEqual(await rawField("events/open", "capacity"), { doubleValue: 10 });
  // A different value is a change, and denied.
  const raise = setEvent("open", eventFields("u2", { capacity: dbl(10.5), attendeeCount: int(5) }), { exists: true });
  assert.equal(await allowed("u3", [createRegistration("open", "u3"), raise]), false);
  // The decimal capacity still limits: 10 places.
  assert.deepEqual(await rawField("events/open", "attendeeCount"), { integerValue: "4" });
});
