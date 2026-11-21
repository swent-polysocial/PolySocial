// Contributors: Claude (Opus 5.5, Anthropic) drafted the deletion of a cancelled event's groups,
// #382.
"use strict";

const { deleteOrphans, runTriggerWork } = require("./registrations");

/**
 * Runs when `events/{eventId}` is deleted ("Cancel event" deletes the event, #130): deletes the
 * event's groups `events/{eventId}/groups/*`, then its one-group-per-event markers
 * `events/{eventId}/groupMembers/*` (#232), with [deleteOrphans]. Once the event is gone no client
 * can start or join a group under it, and none could delete what is left, which is personal data.
 * Each deleted group fires deleteGroupMessages, which deletes its chat. Like the registrations, it
 * stops as soon as the event exists again (the demo seed script writes its events and groups back
 * under the same IDs): what is under it then belongs to the new event. A failure is logged and
 * thrown again by [runTriggerWork], so the trigger runs again until everything is gone.
 *
 * It is its own trigger beside deleteEventRegistrations, on the same deletion: each is retried on
 * its own, and the deployed deleteEventRegistrations keeps its name and its job.
 *
 * @param {object} args
 * @param {string} args.eventId the deleted event's ID.
 * @param {object} args.db Firestore (Admin SDK), which bypasses the Security Rules.
 * @param {{error: function(string, object): void}} args.logger where a failure is logged.
 */
function deleteGroupsOfDeletedEvent({ eventId, db, logger }) {
  return runTriggerWork("deleteEventGroups", logger, async () => {
    const event = db.collection("events").doc(eventId);
    await deleteOrphans(db, event, "groups");
    await deleteOrphans(db, event, "groupMembers");
  });
}

module.exports = { deleteGroupsOfDeletedEvent };
