// Contributors: Claude (the demo seed script for the M2 review, #243).
"use strict";

// Writes (or, with --remove, deletes) the fictional demo data from demoData.js. Usage and the
// one-time sign-in are in the README ("Seeding demo data"). It touches only the demo- documents
// listed in demoData.js and everything under the demo events (including registrations and groups
// real students added to them), never anything else, and takes no key file: on a real project it
// uses the person's own Google Cloud sign-in (gcloud auth application-default login).
const { FieldValue, GeoPoint, Timestamp } = require("firebase-admin/firestore");
const { buildDemoData, demoPaths } = require("./demoData");

/**
 * The options in [argv]: `--project <id>` (always required, never guessed), `--remove`, and on a
 * real project an explicit `--yes`. Under the emulator (FIRESTORE_EMULATOR_HOST in [env]) only a
 * `demo-` project is accepted, and outside it a `demo-` project is refused, so the two can't be
 * mixed up. Throws an Error explaining what's wrong.
 */
function parseArgs(argv, env) {
  let project = null;
  let remove = false;
  let yes = false;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--project") {
      const value = argv[i + 1];
      if (!value || value.startsWith("--")) throw new Error("--project needs a project ID");
      project = value;
      i++;
    } else if (arg === "--remove") remove = true;
    else if (arg === "--yes") yes = true;
    else throw new Error(`Unknown option ${arg}`);
  }
  if (!project) throw new Error("Pass the Firebase project explicitly with --project <id>");
  const emulator = Boolean(env.FIRESTORE_EMULATOR_HOST);
  if (emulator && !project.startsWith("demo-")) {
    throw new Error("Under the emulator, use a demo- project (for example demo-polysocial)");
  }
  if (!emulator && project.startsWith("demo-")) {
    throw new Error("A demo- project only exists in the emulator: start it with firebase emulators:exec");
  }
  if (!emulator && !yes) {
    throw new Error(`This writes to the real project ${project}: add --yes to confirm`);
  }
  return { project, remove };
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
