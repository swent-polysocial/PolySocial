<!-- Contributors: Claude (Opus 5.5, Anthropic) (one-to-one chats and delete-for-me markers in account deletion, and a message deleted meanwhile, #429). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (a sender's unsend of a one-to-one message, #435). -->
<!-- Contributors: Mehdi (@mehdifddl) selected retaining messages with anonymised authors on 2026-11-11; OpenAI Codex documented the implemented cleanup, retained data and deployment order for #241. -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) documented the deletion of a cancelled event's groups and of a deleted group's messages, its guards and known limits, #382. -->

# Groups, chat and account deletion · #241

Account deletion writes `deletedAccounts/{uid}` with profile removal, deletes the
student's event registrations and friendships, takes them out of their one-to-one chats
and deletes their delete-for-me markers (#429, below), removes the sender account link
from all their messages, removes group memberships and per-event markers, then deletes
Auth. Auth remains if a
backend step fails; Retry finishes remaining work. All reads/writes use the Admin SDK.

The merged chat schema (#237) holds exactly `senderId`, `text` and `createdAt`.
Cleanup queries the `messages` collection group on `senderId == uid` in batches of 500,
replacing `senderId` with an empty string. A batch that fails because one of its messages
was deleted meanwhile (`NOT_FOUND`: a chat or group deleted with its messages, #429) is
queried again; the same batch refused twice is a failure, so the step can't loop. This cannot identify an account and is the
explicit deleted-author marker, shown as **Deleted account**. No copied author name or
photo is stored in the schema. Text and server time remain unchanged. Messages in chats
the student left, and below deleted groups/events, are included. No missing public
profile is interpreted as proof of deletion (#291).

Retained text is not redacted and can itself contain personal information. The offline
notice explains this and asks students to avoid including it. Already cached copies on
other devices may persist until refreshed or cleared; deletion does not remotely wipe
them. A marker removes chat read/send access immediately, before membership cleanup.
Clients cannot forge the empty sender, restore an author link or otherwise edit messages: the
only change a client may make is a sender's unsend of their own one-to-one message while the two
are friends (#435), which empties its `text` and nothing else. The cleanup also covers unsent
messages: it empties their `senderId` like any other's, and their text stays empty.

Membership transactions reread groups to preserve concurrent changes. Removing the last
member deletes the group, and the `deleteGroupMessages` trigger then deletes its messages
(below); surviving groups retain their original opaque `createdBy`, as events do. A
malformed array keeps other entries while removing the deleted UID; a list that became a
scalar or disappeared has no remaining UID to remove. These cases emit only a generic
warning, without data, and the student's marker is removed.

After approval and merge, a human deploys from up-to-date `main`: first the restrictive
rules and the new collection-group `messages.senderId` index, wait for the index to be
ready, then the function and app. The existing `groups.memberUids` index is reused.
An agent never deploys. No student receives additional access.

**One-to-one chats (#429).** The message cleanup above covers `chats/*/messages` too
(the same `messages` collection group). Before it, right after the friendships, the
`leave-chats` step reads `chats where uids array-contains <uid>` and decides each chat in
one transaction. While the other student still has an account (their UID is in `uids`
and has no `deletedAccounts` marker), it writes `uids` without the deleted UID: they keep
the conversation, read-only, with the deleted side shown as **Deleted account**. When
nobody else is left (no other UID, or one under a deletion marker, also from a deletion
that stopped earlier), it leaves `uids` unchanged and deletes the messages, then the
chat, so Retry's query still finds an interrupted deletion and no conversation stays
stored that nobody can read. The pair ID keeps the deleted UID as an opaque identifier.
Then the account's own delete-for-me markers, `users/{uid}/hiddenChats/*` and
`users/{uid}/hiddenGroupChats/*`, are deleted in batches of 500 (deleting `users/{uid}`
doesn't delete its subcollections); other students' markers stay. A message its sender
unsent keeps its empty text. Details: the Architecture Diagram, section 3.8 and the
account-deletion matrix.

## Cancelled events and emptied groups · #382

Two Firestore triggers (`firebase/functions/groups.js`, `europe-west1`, deployed with
retries, like #275's `deleteEventRegistrations`) delete what nobody could read or delete
any more:

- **`deleteEventGroups`**, when `events/{eventId}` is deleted ("Cancel event"): the
  event's `groups/*`, then its `groupMembers/*` markers, in transactions of 500 that first
  read the event and stop once it exists again with the deleted event's `createdBy` (the
  demo seed writes its events back under the same IDs and organizers). An event anyone
  else creates under that ID doesn't stop it: a client chooses an event's ID, and the rules
  make `createdBy` the creator's UID. `deleteEventRegistrations` uses the same check.
- **`deleteGroupMessages`**, when `events/{eventId}/groups/{groupId}` is deleted (its last
  member left, through the app or `deleteAccount`, or its event was cancelled): that
  group's `messages/*`, in batches of 500, even if a group is started again under the
  same ID (a client chooses it, and would otherwise read the old chat; the seed writes no
  messages). The event and its other groups keep theirs.

A failure logs only the trigger's name and the error code and is thrown again, so the
trigger's retry finishes the job; deleting what is already gone is not an error. A
client-side batch was rejected: nested messages don't scale, and once the event or group
is gone no client can list what is under it. No rule changes: the Admin SDK bypasses
them.

Known limit: data left behind by events cancelled and groups emptied before the
triggers were deployed stays (a one-off sweep would be its own issue). An account deletion
whose anonymising batch updates a message the trigger has just deleted queries again
(`NOT_FOUND`, #429, above).

A deleted account's messages are still **anonymised, not deleted** (#382 keeps #241's
decision, and #365 chose the same for one-to-one chats): they stay in the conversation
of the students who remain, with an empty `senderId`, until the group itself is deleted.
