// Contributors: Claude Opus 5.5 (drafted the deletion of an account's friendships, #357);
// Konstantinos Perdikeas (directed, reviewed and owns it).
"use strict";

const { deleteAllMatching } = require("./registrations");

/**
 * Deletes every friend request and friendship of the user `uid` (#357): the documents
 * `friendships/{pairId}` whose `uids` contain them, pending or accepted, sent or received. It is
 * the query the app's own listener runs, which needs no composite index. Like the registrations,
 * it runs after the deletion marker is written, so the deleted user's still-valid ID token can't
 * send a request afterwards (the rules refuse a marked sender, and the recipient's public profile
 * is gone, so nobody can send them one either). Deleting a friendship already gone is not an
 * error, so a retry finishes the job.
 */
function deleteUserFriendships(db, uid) {
  return deleteAllMatching(db, db.collection("friendships").where("uids", "array-contains", uid));
}

module.exports = { deleteUserFriendships };
