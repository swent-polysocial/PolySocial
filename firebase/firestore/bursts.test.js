// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372; never merged).
// PROTOTYPE for #372 (never merged): what happens when many students write the same
// event at once, with #373's write-only transactions (repositoryModel.js), and what a registration
// does to the transactions that still read the event: the organizer's save and createGroup.
// Based on the reviewer's contention test. Prints what the emulator did and asserts the outcomes
// the document states.
const { after, before, test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const {
  collection, doc, getDoc, getDocs, increment, runTransaction, serverTimestamp, setDoc, writeBatch,
  Timestamp, GeoPoint,
} = require("firebase/firestore");
const { cancel, register } = require("./repositoryModel");

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-polysocial",
    firestore: { rules: readFileSync(process.env.RULES_FILE || path.join(__dirname, "firestore.rules"), "utf8") },
  });
});
after(async () => { await env.cleanup(); });

const studentDb = (uid) =>
  env.authenticatedContext(uid, { email: "student@epfl.ch", email_verified: true }).firestore();
async function admin(run) {
  let result;
  await env.withSecurityRulesDisabled(async (context) => { result = await run(context.firestore()); });
  return result;
}
function eventBy(uid, overrides = {}) {
  return {
    title: "Study session", description: "", category: "STUDY", location: new GeoPoint(46.5, 6.5),
    startTime: Timestamp.fromDate(new Date("2099-01-01T18:00:00Z")), endTime: null, capacity: 1000,
    isPrivate: false, createdBy: uid, organizerIds: [uid], allowedUids: [uid],
    isAssociationEvent: false, attendeeCount: 0, ...overrides,
  };
}
async function reset(eventId, overrides) {
  await env.clearFirestore();
  await admin((db) => setDoc(doc(db, `events/${eventId}`), eventBy("u2", overrides)));
}
async function endState(eventId) {
  return admin(async (db) => {
    const event = await getDoc(doc(db, `events/${eventId}`));
    const attendees = await getDocs(collection(db, `events/${eventId}/attendees`));
    return { count: event.data().attendeeCount, registrations: attendees.size };
  });
}
function tally(results) {
  const counts = {};
  for (const result of results) counts[result] = (counts[result] || 0) + 1;
  return counts;
}
const students = (prefix, n) => Array.from({ length: n }, (_, i) => `${prefix}${i}`);

/** The organizer's save as writeAsOrganizer does it: reads the event, then updates it. */
async function organizerSave(eventId, latencyMs, options) {
  const db = studentDb("u2");
  try {
    await runTransaction(db, async (transaction) => {
      const event = doc(db, `events/${eventId}`);
      await transaction.get(event);
      if (latencyMs) await sleep(latencyMs);
      transaction.update(event, { title: "Moved to Rolex" });
    }, options);
    return "Updated";
  } catch (error) {
    return `Error(${error.code})`;
  }
}
/** createGroup's shape: reads the event and the caller's marker, writes the group and the marker. */
async function createGroup(uid, eventId, latencyMs, options, afterReads) {
  const db = studentDb(uid);
  try {
    return await runTransaction(db, async (transaction) => {
      const marker = doc(db, `events/${eventId}/groupMembers/${uid}`);
      if (!(await transaction.get(doc(db, `events/${eventId}`))).exists()) return "NotFound";
      if ((await transaction.get(marker)).exists()) return "AlreadyInGroup";
      if (afterReads) await afterReads();
      if (latencyMs) await sleep(latencyMs);
      const group = doc(collection(db, `events/${eventId}/groups`));
      transaction.set(group, { capacity: 4, memberUids: [uid], createdBy: uid, createdAt: serverTimestamp() });
      transaction.set(marker, { groupId: group.id });
      return "Created";
    }, options);
  } catch (error) {
    return `Error(${error.code})`;
  }
}
/** One student registering and cancelling with plain batches and increments, every [pauseMs]. */
function startLoop(uid, eventId, pauseMs) {
  const db = studentDb(uid);
  const state = { stop: false, writes: 0, denied: 0 };
  state.done = (async () => {
    let registered = false;
    while (!state.stop) {
      const batch = writeBatch(db);
      const registration = doc(db, `events/${eventId}/attendees/${uid}`);
      if (registered) {
        batch.delete(registration);
        batch.update(doc(db, `events/${eventId}`), { attendeeCount: increment(-1) });
      } else {
        batch.set(registration, { uid, registeredAt: serverTimestamp() });
        batch.update(doc(db, `events/${eventId}`), { attendeeCount: increment(1) });
      }
      try { await batch.commit(); registered = !registered; state.writes++; } catch (_) { state.denied++; }
      if (pauseMs) await sleep(pauseMs);
    }
  })();
  return state;
}

for (const n of [5, 10, 30]) {
  test(`B${n}: ${n} students tap "I'm going" at once, places for all`, async () => {
    await reset("hot");
    const results = await Promise.all(students("s", n).map((uid) => register(studentDb(uid), uid, "hot")));
    const state = await endState("hot");
    console.log(`BURST B${n}: ${JSON.stringify(tally(results))} | ${JSON.stringify(state)}`);
    assert.deepEqual(tally(results), { Registered: n });
    assert.deepEqual(state, { count: n, registrations: n });
  });
}

test("B-legacy: 10 students at once on an event without attendeeCount", async () => {
  await env.clearFirestore();
  await admin((db) => setDoc(doc(db, "events/hot"), {
    isPrivate: false, createdBy: "u2", allowedUids: ["u2"], startTime: Timestamp.fromDate(new Date("2099-01-01T18:00:00Z")),
  }));
  const results = await Promise.all(students("s", 10).map((uid) => register(studentDb(uid), uid, "hot")));
  const state = await endState("hot");
  console.log(`BURST B-legacy: ${JSON.stringify(tally(results))} | ${JSON.stringify(state)}`);
  assert.deepEqual(tally(results), { Registered: 10 });
  assert.deepEqual(state, { count: 10, registrations: 10 });
});

for (const [n, places] of [[12, 4], [30, 4], [30, 29]]) {
  test(`B-cap: ${n} students at once for ${places} places`, async () => {
    await reset("hot", { capacity: places });
    const steps = students("s", n).map(() => []);
    const results = await Promise.all(
      students("s", n).map((uid, i) => register(studentDb(uid), uid, "hot", steps[i])),
    );
    const state = await endState("hot");
    const refusals = tally(steps.filter((taken) => taken[0] !== "write").map((taken) => taken[0]));
    console.log(`BURST B-cap(${n} for ${places}): ${JSON.stringify(tally(results))} | ${JSON.stringify(state)} | refused writes ended in ${JSON.stringify(refusals)}`);
    assert.deepEqual(tally(results), { Registered: places, Full: n - places });
    assert.deepEqual(state, { count: places, registrations: places });
  });
}

// Why the transactions use the SDK's default attempts: tried once, the emulator answers part of a
// burst of refused commits with "ABORTED: Transaction lock timeout" instead of the refusal, and a
// student who still had a place can get it too.
for (const [n, places] of [[12, 4], [30, 29]]) {
  test(`B-once: ${n} students at once for ${places} places, each transaction tried once (observation)`, async () => {
    await reset("hot", { capacity: places });
    const steps = students("s", n).map(() => []);
    const results = await Promise.all(
      students("s", n).map((uid, i) => register(studentDb(uid), uid, "hot", steps[i], { maxAttempts: 1 })),
    );
    const state = await endState("hot");
    const refusals = tally(steps.filter((taken) => taken[0] !== "write").map((taken) => taken[0]));
    console.log(`BURST B-once(${n} for ${places}): ${JSON.stringify(tally(results))} | ${JSON.stringify(state)} | refused writes ended in ${JSON.stringify(refusals)}`);
    // Never more registrations than places, and the counter always matches them.
    assert.equal(state.count, state.registrations);
    assert.ok(state.count <= places);
  });
}

test("B-cancel: 30 registered students cancel at once", async () => {
  await reset("hot");
  await Promise.all(students("s", 30).map((uid) => register(studentDb(uid), uid, "hot")));
  const results = await Promise.all(students("s", 30).map((uid) => cancel(studentDb(uid), uid, "hot")));
  const state = await endState("hot");
  console.log(`BURST B-cancel: ${JSON.stringify(tally(results))} | ${JSON.stringify(state)}`);
  assert.deepEqual(tally(results), { Cancelled: 30 });
  assert.deepEqual(state, { count: 0, registrations: 0 });
});

test("B-mixed: 15 students register while 15 others cancel", async () => {
  await reset("hot");
  await Promise.all(students("old", 15).map((uid) => register(studentDb(uid), uid, "hot")));
  const results = await Promise.all([
    ...students("new", 15).map((uid) => register(studentDb(uid), uid, "hot")),
    ...students("old", 15).map((uid) => cancel(studentDb(uid), uid, "hot")),
  ]);
  const state = await endState("hot");
  console.log(`BURST B-mixed: ${JSON.stringify(tally(results))} | ${JSON.stringify(state)}`);
  assert.deepEqual(tally(results), { Registered: 15, Cancelled: 15 });
  assert.deepEqual(state, { count: 15, registrations: 15 });
});

test("G: one registration overtakes an organizer's save and a createGroup", async () => {
  await reset("hot");
  const report = {};
  for (const [name, options] of [["tried once", { maxAttempts: 1 }], ["default attempts", undefined]]) {
    let overtaken = false;
    report[`createGroup, ${name}`] = await createGroup(`g-${name}`, "hot", 0, options, async () => {
      if (overtaken) return;
      overtaken = true;
      await register(studentDb(`r-${name}`), `r-${name}`, "hot");
    });
    // The save sleeps 300 ms between its read and its commit; the registration lands in between.
    const save = organizerSave("hot", 300, options);
    await sleep(100);
    await register(studentDb(`q-${name}`), `q-${name}`, "hot");
    report[`organizer save, ${name}`] = await save;
  }
  console.log(`BURST G: ${JSON.stringify(report)} | ${JSON.stringify(await endState("hot"))}`);
  assert.deepEqual(report, {
    "createGroup, tried once": "Error(failed-precondition)",
    "organizer save, tried once": "Error(failed-precondition)",
    "createGroup, default attempts": "Created",
    "organizer save, default attempts": "Updated",
  });
});

for (const [pauseMs, latencyMs] of [[100, 200], [500, 200]]) {
  test(`L: one student registers and cancels every ${pauseMs} ms; the others' reads and commits are ${latencyMs} ms apart`, async () => {
    await reset("busy");
    const loop = startLoop("looper", "busy", pauseMs);
    const started = Date.now();
    await sleep(300);
    const registered = [];
    for (const uid of students("b", 15)) registered.push(await register(studentDb(uid), uid, "busy"));
    const cancelled = [];
    for (const uid of students("b", 5)) cancelled.push(await cancel(studentDb(uid), uid, "busy"));
    const savesOnce = [];
    for (let i = 0; i < 6; i++) savesOnce.push(await organizerSave("busy", latencyMs, { maxAttempts: 1 }));
    const savesDefault = [];
    for (let i = 0; i < 6; i++) savesDefault.push(await organizerSave("busy", latencyMs, undefined));
    const groups = [];
    for (const uid of students("g", 6)) groups.push(await createGroup(uid, "busy", latencyMs, undefined));
    loop.stop = true;
    await loop.done;
    const seconds = (Date.now() - started) / 1000;
    console.log(`BURST L(${pauseMs},${latencyMs}) loop: ${loop.writes} writes in ${seconds.toFixed(1)} s (${(loop.writes / seconds).toFixed(1)}/s), ${loop.denied} denied`);
    console.log(`BURST L(${pauseMs},${latencyMs}) register, write-only: ${JSON.stringify(tally(registered))}`);
    console.log(`BURST L(${pauseMs},${latencyMs}) cancel, write-only: ${JSON.stringify(tally(cancelled))}`);
    console.log(`BURST L(${pauseMs},${latencyMs}) organizer save, tried once: ${JSON.stringify(tally(savesOnce))}`);
    console.log(`BURST L(${pauseMs},${latencyMs}) organizer save, default attempts: ${JSON.stringify(tally(savesDefault))}`);
    console.log(`BURST L(${pauseMs},${latencyMs}) createGroup, default attempts: ${JSON.stringify(tally(groups))}`);
    assert.deepEqual(tally(registered), { Registered: 15 });
    assert.deepEqual(tally(cancelled), { Cancelled: 5 });
  });
}
