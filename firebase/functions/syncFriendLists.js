// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #447 under Lucas Pinto Ricardo’s supervision and approval).
"use strict";

const { Timestamp } = require("firebase-admin/firestore");

/**
 * Sets both profile list entries from the current friendship. The pair ID also identifies the
 * rows to delete after the source document is gone. Delivery order and duplicates are immaterial.
 */
async function syncFriendLists(db, pairId) {
  const [first, second, extra] = pairId.split("~");
  if (!first || !second || extra !== undefined || first >= second) return;

  const friendship = db.collection("friendships").doc(pairId);
  const entries = [
    db.doc(`publicProfiles/${first}/friends/${second}`),
    db.doc(`publicProfiles/${second}/friends/${first}`),
  ];
  await db.runTransaction(async (transaction) => {
    const current = await transaction.get(friendship);
    const acceptedAt = current.get("acceptedAt");
    const listed = await transaction.getAll(...entries);
    if (current.get("status") === "accepted" && acceptedAt instanceof Timestamp) {
      for (const entry of listed) {
        const stored = entry.get("acceptedAt");
        if (!(stored instanceof Timestamp) || !stored.isEqual(acceptedAt)) {
          transaction.set(entry.ref, { acceptedAt });
        }
      }
    } else {
      for (const entry of listed) {
        if (entry.exists) transaction.delete(entry.ref);
      }
    }
  });
}

module.exports = { syncFriendLists };
