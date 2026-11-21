// Contributors: Claude (Opus 5.5, Anthropic) drafted the deletion of a cancelled event's groups and
// of a deleted group's messages, #382; the messages deleted even under a re-created group, and
// the event's guard on its creator, after review.
"use strict";

const { deleteAllMatching, deleteOrphans, runTriggerWork } = require("./registrations");

/**
 * Runs when `events/{eventId}` is deleted ("Cancel event" deletes the event, #130): deletes the
 * event's groups `events/{eventId}/groups/*`, then its one-group-per-event markers
 * `events/{eventId}/groupMembers/*` (#232), with [deleteOrphans]. Once the event is gone no client
 * can start or join a group under it, and none could delete what is left, which is personal data.
 * Each deleted group fires deleteGroupMessages, which deletes its chat. Like the registrations, it
 * stops as soon as the event exists again written by the same creator (the demo seed script writes
 * its events and groups back under the same IDs): what is under it then belongs to the new event.
 * A failure is logged and thrown again by [runTriggerWork], so the trigger runs again until
 * everything is gone.
 *
 * It is its own trigger beside deleteEventRegistrations, on the same deletion: each is retried on
 * its own, and the deployed deleteEventRegistrations keeps its name and its job.
 *
 * @param {object} args
 * @param {string} args.eventId the deleted event's ID.
 * @param {object|undefined} args.deleted the deleted event's data, for its `createdBy`.
 * @param {object} args.db Firestore (Admin SDK), which bypasses the Security Rules.
 * @param {{error: function(string, object): void}} args.logger where a failure is logged.
 */
function deleteGroupsOfDeletedEvent({ eventId, deleted, db, logger }) {
  return runTriggerWork("deleteEventGroups", logger, async () => {
    const event = db.collection("events").doc(eventId);
    await deleteOrphans(db, event, "groups", deleted?.createdBy);
    await deleteOrphans(db, event, "groupMembers", deleted?.createdBy);
  });
}

/**
 * Runs when `events/{eventId}/groups/{groupId}` is deleted: by its last member leaving (#232's
 * leave, and deleteAccount's membership step, #241) or by [deleteGroupsOfDeletedEvent]. Deletes
 * the group's chat messages `.../messages/*` (#237), with [deleteAllMatching]: the rules let no
 * client delete a message, and once the group is gone nobody can read or send one (membership
 * comes from the group's `memberUids`). Only this group's messages go; the event and its other
 * groups keep theirs. It deletes them even if a group exists again under the same ID: a client
 * chooses the ID of a group it starts (the rules don't check it), so stopping there would let a
 * student start a group under a just-emptied group's ID and read its old chat. The demo seed
 * script writes no messages and deletes its groups with everything under them, so it needs no
 * such stop. A failure is logged and thrown again by [runTriggerWork], so the trigger runs again
 * until the messages are gone.
 *
 * @param {object} args
 * @param {string} args.eventId the deleted group's event ID.
 * @param {string} args.groupId the deleted group's ID.
 * @param {object} args.db Firestore (Admin SDK), which bypasses the Security Rules.
 * @param {{error: function(string, object): void}} args.logger where a failure is logged.
 */
function deleteMessagesOfDeletedGroup({ eventId, groupId, db, logger }) {
  return runTriggerWork("deleteGroupMessages", logger, () => {
    const group = db.collection("events").doc(eventId).collection("groups").doc(groupId);
    return deleteAllMatching(db, group.collection("messages"));
  });
}

module.exports = { deleteGroupsOfDeletedEvent, deleteMessagesOfDeletedGroup };
