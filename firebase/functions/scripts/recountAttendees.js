// Contributors: Claude (Opus 5.5, Anthropic) (the one-off attendee recount, from #372's prototype,
// #405).
"use strict";

// Gives every event its `attendeeCount`: the number of its registrations. Run once by a person,
// right after #373's Security Rules are deployed, so that events registered for before the counter
// existed start with the right value, and again if a counter ever drifts. Usage and the one-time
// sign-in are in the README ("Recounting attendees"). It never runs in CI or from an agent, takes
// no key file (on a real project it uses the person's own Google Cloud sign-in), and logs only
// counts, never an event or a student.
const { parseProjectArgs } = require("./projectArgs");

/**
 * Sets every event's `attendeeCount` to the number of its registrations
 * (`events/{eventId}/attendees/*`), each event in its own transaction, so a registration or a
 * cancellation landing meanwhile can't be lost. It always writes the count, even when the stored
 * value reads equal: Node reads a counter stored as 3.0 as 3, such a counter locks the event under
 * #373's rules, and a whole JavaScript number is written back as an integer. Every other field is
 * left alone, and an event deleted meanwhile is skipped. Running it again writes the same counts.
 *
 * @returns {Promise<{events: number, changed: number}>} how many events were recounted, and how
 *   many of them had another count (or none) before.
 */
async function recountAttendees(db) {
  const events = await db.collection("events").select().get();
  let changed = 0;
  for (const event of events.docs) changed += await recountEvent(db, event.ref);
  return { events: events.size, changed };
}

/** Recounts the event [ref] in one transaction; 1 if its count was another, 0 otherwise. */
function recountEvent(db, ref) {
  return db.runTransaction(async (transaction) => {
    const stored = await transaction.get(ref);
    if (!stored.exists) return 0;
    const counted = await transaction.get(ref.collection("attendees").count());
    const count = counted.data().count;
    transaction.update(ref, { attendeeCount: count });
    return stored.get("attendeeCount") === count ? 0 : 1;
  });
}

async function main() {
  const { project } = parseProjectArgs(process.argv.slice(2), process.env);
  const { deleteApp, initializeApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const app = initializeApp({ projectId: project });
  try {
    const { events, changed } = await recountAttendees(getFirestore(app));
    process.stdout.write(
      `Recounted ${events} events in ${project}; ${changed} had another attendeeCount.\n`,
    );
  } finally {
    await deleteApp(app);
  }
}

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}

module.exports = { recountAttendees };
