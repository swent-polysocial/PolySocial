// Contributors: Claude (the demo seed script for the M2 review, #243).
// Contributors: Claude (Opus 5.5, Anthropic) (its options shared through projectArgs.js, #405).
"use strict";

// Writes (or, with --remove, deletes) the fictional demo data from demoData.js. Usage and the
// one-time sign-in are in the README ("Seeding demo data"). It touches only the demo- documents
// listed in demoData.js and everything under the demo events (including registrations and groups
// real students added to them), never anything else, and takes no key file: on a real project it
// uses the person's own Google Cloud sign-in (gcloud auth application-default login).
const { FieldValue, GeoPoint, Timestamp } = require("firebase-admin/firestore");
const { buildDemoData, demoPaths } = require("./demoData");
const { parseProjectArgs } = require("./projectArgs");

/**
 * The options in [argv]: `--project <id>`, `--remove`, and on a real project `--yes`, as
 * [parseProjectArgs] checks them. Throws an Error explaining what's wrong.
 */
function parseArgs(argv, env) {
  const { project, flags } = parseProjectArgs(argv, env, ["remove"]);
  return { project, remove: flags.remove };
}

/**
 * Writes every demo document as of [now], in one batch, after [removeDemo]: the scenario starts
 * again from scratch, without students who went to or joined a demo event since the last run.
 */
async function seedDemo(db, now) {
  await removeDemo(db);
  const docs = buildDemoData({
    now,
    geoPoint: (latitude, longitude) => new GeoPoint(latitude, longitude),
    timestamp: (date) => Timestamp.fromDate(date),
    serverTime: () => FieldValue.serverTimestamp(),
  });
  const batch = db.batch();
  for (const { path, data } of docs) batch.set(db.doc(path), data);
  await batch.commit();
  return docs.length;
}

/**
 * Deletes every demo document: each demo event with everything under it, whoever wrote it
 * (registrations, groups and markers real students added only exist because of the fictional
 * event), and the other demo documents by path. Missing ones are fine; nothing else is touched.
 */
async function removeDemo(db) {
  const paths = demoPaths();
  const events = paths.filter((path) => /^events\/[^/]+$/.test(path));
  await Promise.all(events.map((path) => db.recursiveDelete(db.doc(path))));
  const batch = db.batch();
  for (const path of paths.filter((path) => !path.startsWith("events/"))) batch.delete(db.doc(path));
  await batch.commit();
  return paths.length;
}

async function main() {
  const { project, remove } = parseArgs(process.argv.slice(2), process.env);
  const { deleteApp, initializeApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const app = initializeApp({ projectId: project });
  try {
    const db = getFirestore(app);
    const count = remove ? await removeDemo(db) : await seedDemo(db, new Date());
    process.stdout.write(`${remove ? "Removed" : "Wrote"} ${count} demo documents in ${project}.\n`);
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

module.exports = { parseArgs, removeDemo, seedDemo };
