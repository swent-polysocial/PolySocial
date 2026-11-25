// Contributors: Claude (Sonnet 5.5, Anthropic) (the one-off backfill of allowedSections, from the
// attendee recount's script, #369).
"use strict";

// Gives every event its `allowedSections`, [] for every section. Run once by a person, before
// #369's Security Rules are deployed: those rules treat an event without the field as closed to
// students, so events created before the field existed would disappear from the app until this has
// run. Usage and the deploy order are in the README ("Backfilling allowedSections"). It never runs
// in CI or from an agent, takes no key file (on a real project it uses the person's own Google
// Cloud sign-in), and logs only counts, never an event or a student.
const { parseProjectArgs } = require("./projectArgs");

/**
 * Sets `allowedSections` to [] on every event that doesn't have it as a list, each event in its own
 * transaction, so an organizer saving sections meanwhile (a list already) isn't overwritten. An
 * event that has it is left alone, as is every other field, and one deleted meanwhile is skipped.
 * Running it again changes nothing.
 *
 * @returns {Promise<{events: number, changed: number}>} how many events it looked at, and how many
 *   it gave the field.
 */
async function backfillAllowedSections(db) {
  const events = await db.collection("events").select("allowedSections").get();
  let changed = 0;
  for (const event of events.docs) changed += await backfillEvent(db, event.ref);
  return { events: events.size, changed };
}

/** Gives the event [ref] its field in one transaction; 1 if it was missing, 0 otherwise. */
function backfillEvent(db, ref) {
  return db.runTransaction(async (transaction) => {
    const stored = await transaction.get(ref);
    if (!stored.exists || Array.isArray(stored.get("allowedSections"))) return 0;
    transaction.update(ref, { allowedSections: [] });
    return 1;
  });
}

async function main() {
  const { project } = parseProjectArgs(process.argv.slice(2), process.env);
  const { deleteApp, initializeApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const app = initializeApp({ projectId: project });
  try {
    const { events, changed } = await backfillAllowedSections(getFirestore(app));
    process.stdout.write(
      `Looked at ${events} events in ${project}; gave allowedSections to ${changed}.\n`,
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

module.exports = { backfillAllowedSections };
