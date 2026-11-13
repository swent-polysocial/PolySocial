<!-- Contributors: Mehdi (@mehdifddl) selected retaining messages with anonymised authors on 2026-11-11; OpenAI Codex documented the implemented cleanup, retained data and deployment order for #241. -->

# Groups, chat and account deletion · #241

Account deletion writes `deletedAccounts/{uid}` with profile removal, deletes the
student's event registrations, removes the sender account link from all their messages,
removes group memberships and per-event markers, then deletes Auth. Auth remains if a
backend step fails; Retry finishes remaining work. All reads/writes use the Admin SDK.

The merged chat schema (#237) holds exactly `senderId`, `text` and `createdAt`.
Cleanup queries the `messages` collection group on `senderId == uid` in batches of 500,
replacing `senderId` with an empty string. This cannot identify an account and is the
explicit deleted-author marker, shown as **Deleted account**. No copied author name or
photo is stored in the schema. Text and server time remain unchanged. Messages in chats
the student left, and below deleted groups/events, are included. No missing public
profile is interpreted as proof of deletion (#291).

Retained text is not redacted and can itself contain personal information. The offline
notice explains this and asks students to avoid including it. Already cached copies on
other devices may persist until refreshed or cleared; deletion does not remotely wipe
them. A marker removes chat read/send access immediately, before membership cleanup.
Clients cannot forge the empty sender, edit messages or restore an author link.

Membership transactions reread groups to preserve concurrent changes. Removing the last
member deletes the group; surviving groups retain their original opaque `createdBy`, as
events do. A malformed array keeps other entries while removing the deleted UID; a list
that became a scalar or disappeared has no remaining UID to remove. These cases emit
only a generic warning, without data, and the student's marker is removed.

After approval and merge, a human deploys from up-to-date `main`: first the restrictive
rules and the new collection-group `messages.senderId` index, wait for the index to be
ready, then the function and app. The existing `groups.memberUids` index is reused.
An agent never deploys. No student receives additional access.

Deleting an event's groups/messages, and removing orphaned messages when the last member
leaves normally, remain separate work. Account deletion still removes their sender link.
