// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #447 under Lucas Pinto Ricardo’s supervision and approval).
"use strict";

const { parseProjectArgs } = require("./projectArgs");
const { syncFriendLists } = require("../syncFriendLists");

/** Reconciles each accepted friendship with the same transaction as the live trigger. */
async function backfillFriendLists(db) {
  const accepted = db.collection("friendships").where("status", "==", "accepted").select();
  let count = 0;
  for await (const friendship of accepted.stream()) {
    await syncFriendLists(db, friendship.id);
    count++;
  }
  return count;
}

async function main() {
  const { project } = parseProjectArgs(process.argv.slice(2), process.env);
  const { deleteApp, initializeApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const app = initializeApp({ projectId: project });
  try {
    const count = await backfillFriendLists(getFirestore(app));
    process.stdout.write(`Reconciled ${count} accepted friendships.\n`);
  } finally {
    await deleteApp(app);
  }
}

if (require.main === module) {
  main().catch((error) => {
    // An Admin SDK error can contain document paths. Keep student identifiers out of the output.
    const message = error.code === undefined ? error.message : `Firestore error (${error.code}).`;
    process.stderr.write(`${message}\n`);
    process.exitCode = 1;
  });
}

module.exports = { backfillFriendLists };
