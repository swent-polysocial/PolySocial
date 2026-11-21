// Contributors: Claude (Opus 5.5, Anthropic) drafted the one-to-one chats' account-deletion step,
// from #365's prototype, #429; malformed uids tolerated, after review.
"use strict";

const { deleteAllMatching } = require("./registrations");

/**
 * Takes the deleted account `uid` out of its one-to-one chats `chats/{pairId}` (#365), each with
 * [leaveChat], then deletes its delete-for-me markers `users/{uid}/hiddenChats/*` and
 * `users/{uid}/hiddenGroupChats/*`, in batches of 500 until none is left (deleteAllMatching):
 * deleting `users/{uid}` doesn't delete its subcollections. Other students' markers are theirs and stay.
 *
 * It runs after the friendships are deleted: from then on no chat can be created for the UID (that
 * needs an accepted friendship), and the deletion marker already refuses the UID's still-valid ID
 * token every chat and marker write. So the chats are read once, and a retry after a failure finds
 * only unfinished work: every chat handled before it either no longer lists the UID or is gone.
 */
async function leaveUserChats(db, uid) {
  const chats = await db.collection("chats").where("uids", "array-contains", uid).get();
  for (const chat of chats.docs) await leaveChat(db, chat.ref, uid);
  for (const markers of ["hiddenChats", "hiddenGroupChats"]) {
    await deleteAllMatching(db, db.collection(`users/${uid}/${markers}`));
  }
}

/**
 * One chat of the deleted account `uid`: a transaction reads it, with the deletion markers of the
 * other UIDs in its `uids`, and decides.
 * - The other student still has an account (their UID is in `uids` and has no `deletedAccounts`
 *   marker): it writes `uids` without the deleted UID. The other student keeps the chat, read-only,
 *   and finds it with the same query; a `uids` without another UID is how the app knows to show
 *   "Deleted account".
 * - Nobody else is left (no other UID, or one under a deletion marker, also from a deletion that
 *   stopped before this step and may never be retried): it writes nothing. Then the chat's messages
 *   are deleted, then the chat. `uids` keeps the UID until the chat is gone, because that is how
 *   [leaveUserChats]'s query finds a deletion interrupted in between: an empty `uids` written first
 *   would hide it from a retry and keep both students' texts for good.
 * A chat already gone when the transaction reads it (another deletion got there first), or no longer
 * holding a list, is skipped. An entry of `uids` that isn't a non-empty string can't be a UID, so it
 * isn't an account and is kept as it is, as #241's group step does with `memberUids`: a malformed
 * chat can't stop the deletion.
 */
async function leaveChat(db, chat, uid) {
  const nobodyLeft = await db.runTransaction(async (transaction) => {
    const uids = (await transaction.get(chat)).get("uids");
    if (!Array.isArray(uids)) return false;
    const others = uids.filter((member) => member !== uid);
    for (const other of others) {
      if (typeof other !== "string" || other === "") continue;
      if (!(await transaction.get(db.collection("deletedAccounts").doc(other))).exists) {
        transaction.update(chat, { uids: others });
        return false;
      }
    }
    return true;
  });
  if (!nobodyLeft) return;
  await deleteAllMatching(db, chat.collection("messages"));
  await chat.delete();
}

module.exports = { leaveUserChats };
