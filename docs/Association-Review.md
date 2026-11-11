<!-- Contributors: OpenAI Codex (drafted the review workflow and access matrix, then clarified console operations, pending outcomes and cache behavior, #210). -->

# Association review and rejection

The [manual Firebase console procedure][console] is the Sprint 3 delivery.
This workflow documents the checks a project owner can make today.

## Who can review

A PolySocial admin with the Owner role on the app's Firebase project checks
completed pending setups manually. The Firebase console lists current Owners
under **Project settings → Users and permissions**. If a reviewer belongs to
the applicant association, another owner handles that review. Keep applicant
details and evidence out of public issues. Use only the information needed
for the decision.

## Manual review

1. Match the pending `associations/{uid}` setup to the same UID in Firebase
   Authentication and `users/{uid}`. Confirm the email is verified and the
   account type is `association`.
2. Check the submitted name and type against an official EPFL directory or the
   association’s official website. Look for independent confirmation that the
   applicant can represent the association. Both EPFL and non-EPFL addresses
   can be valid. An email domain or a link supplied by the applicant is
   insufficient on its own.
3. Check for an existing account for the same association, including variants of
   its name. If an approved account already exists, keep its approval and leave
   the duplicate pending. If two pending accounts claim the same association,
   leave both pending until there is independent confirmation of which one
   should represent it.
4. Approve a confirmed request using the [console procedure][console]. Set
   `users/{uid}.isAssociationVerified` to boolean `true`, then re-check the reviewed
   identity. If it changed, revoke approval and review again. The account opens
   its association tabs once the app reads the updated profile on startup.
5. If the owner declines approval or needs more evidence, leave the flag absent
   or `false`. The app still shows pending review. The applicant can edit
   permitted setup details, and any owner can check them again in the console.
   The console workflow sends no rejection notice and provides no separate
   resubmission action.

## Revocation and cleanup

To revoke approval, set the boolean to `false` using the
[console procedure][console]. The server then denies verified-only operations.
The app routes to pending review once startup reads the updated profile. An
offline device may still have the previous flag and cached events or identity.

The Firebase console cannot clear copies on Android devices. On a device that
needs its local Firestore cache cleared, use **Log out** in the app. Logout
clears that device's Firestore persistence before signing out and offers
**Retry** if cleanup fails. The app also clears the cache before allowing a
different account to use it. Revocation alone does not clear either device
cache or server data.

Public events published while an association was approved stay visible after
revocation. They can still show the Verified badge because it comes from the
event's stored `isAssociationEvent` field. The badge grants no access. A
never-approved association cannot publish an association event, so declining
its setup cannot leave an old badge. Removing or changing a published event
is a separate action.

Rejected and duplicate accounts retain verified email addresses, so
[#226's planned cleanup][unverified-cleanup] would skip them. Removing a
Firebase Authentication account alone would leave its Firestore records.
The [association account deletion procedure][association-deletion] still needs
to define who removes the Auth account and its Firestore identity and how to
handle related events and records. It also needs to account for still-valid
sign-in tokens when removing the profile that identifies the account as an
association.

## Access matrix

| Actor | Allowed | Denied |
| --- | --- | --- |
| Project owner in Firebase console | Review the association, approve or revoke using the documented procedure | Future in-app privileges require a separate authorization decision |
| Verified student | Existing student access, including reading an approved association’s public identity by UID | Read another account’s private profile or a pending association’s identity, approve or revoke |
| Verified-email association pending review, rejected or reapplying | Own private account and own association setup | Publishing events, other associations’ data, student profiles, groups, chats, matching, approval writes |
| Approved association | Own private account and identity, read and manage its own events, edit allowed identity fields | Change the approved name or type, access other associations’ data or student profiles, groups, chats and matching, approval writes |
| Signed out or email unverified | Sign-in and email verification | Protected Firestore data and review operations |
| Future authorized reviewer | Read only review records needed for the assigned role and perform explicitly authorized review operations through a trusted backend | Client writes to the approval flag, unrelated student data, generic database access |

A future review interface must verify an explicitly assigned reviewer role on
the server before every operation. Sign-in, an EPFL email address and a
client-writable account role cannot grant reviewer access. Client code never
writes `isAssociationVerified`, including when a reviewer uses the interface.
The current rules allow a client to initialize its own profile with the flag
set to `false`. Client updates to the flag are denied.

## Review cases

- **Approve.** An independently contacted committee confirms which account it
  controls. The owner approves it and checks that its name and type match the
  evidence. It gains association event access and retains the association
  restrictions in the matrix.
- **Duplicate.** A second account requests approval for an association already
  represented in the app. It remains pending while the committee recovers the
  existing account. The existing approval stays with that account.
- **Decline and revisit.** The owner cannot confirm the applicant's authority
  and leaves the account pending. An owner can revisit corrected details in
  the console using the same checks.
- **Revoke.** The owner sets the flag to `false`. A connected app that restarts
  and reads the updated profile shows pending review. Previously published
  events and device caches follow the cleanup notes above. Any later approval
  requires rechecking the name and type.

## Product backlog wording for the board owner

> Sprint 3 delivered manual Firebase-console verification via #135. #210
> documents the manual review and what happens when approval is declined or
> revoked. A future admin interface, including any rejection notice or
> resubmission action, needs its own agreed scope and server-side authorization.

The board owner applies this wording to the product draft after merge.

[console]: ../README.md#verifying-an-association
[unverified-cleanup]: https://github.com/swent-polysocial/PolySocial/issues/226
[association-deletion]: ../CONTEXT.md#open-questions-dont-guess-ask
