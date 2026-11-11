<!-- Contributors: Mehdi (@mehdifddl) selected retaining messages with anonymised authors on 2026-11-11; OpenAI Codex documented the decision, implementation boundary and dependency for #241. -->

# Groups, chat and account deletion · #241

The current branch removes the student's membership from every event group and deletes
their per-event `groupMembers` marker before deleting their Auth user. A transaction reads
each group again, preserving other students' concurrent changes. The last member's group
is deleted, following `GroupRepository.leaveGroup`; a surviving group retains its original
opaque `createdBy`, as events already do. A failed cleanup returns an error and Retry
finishes it. The existing `groups.memberUids` collection-group index supports the query.

The profile-deletion transaction first writes `deletedAccounts/{uid}`. Changed rules deny
starting or joining groups and creating membership markers under that UID, even with a
still-valid token, so another device cannot recreate membership after the cleanup query.
Ordinary leave remains allowed. Rules and function deployment are manual: after teammate
review and merge, a person deploys the rules before enabling the updated function. An
agent never deploys. No student receives additional access.

Mehdi selected **retaining messages with anonymised authors**, rather than deleting the
conversation. For #237 integration, remove the link to the deleted account and any copied
author identity, preserve the message content and time, and render a neutral deleted-author
label. A missing public profile alone must not be treated as proof of deletion (#291).
The exact stored fields must come from the merged chat implementation; this branch does
not invent or write a future message schema. The text itself remains what its author sent.

**#241 remains a draft until #237 is integrated.** There is no chat model, repository or
screen on the current `main`, so no message anonymisation is implemented here. Once it
lands, add sender cleanup to `deleteAccount` before Auth deletion, cover partial failure,
retry and still-valid-token races with unit, rules and emulator tests, and replace both
offline notice entries with the actual fields and access policy. Do not claim the future
policy is already available. The public-profile cleanup is already provided by #229.
