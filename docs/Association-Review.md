<!-- Contributors: OpenAI Codex (drafted the workflow and access matrix, then revised intake, evidence handling and revocation after review, #210). -->

# Association review and rejection

The [manual Firebase console procedure][console] is the Sprint 3 delivery.
This workflow covers intake, evidence, duplicates, rejection and reapplication.

## Review setup

The team assigns a primary reviewer and a backup from the PolySocial admins
with the Owner role on the app's Firebase project. In the Firebase console,
open **Project settings → Users and permissions** to see the current Owners.
The primary checks for completed association setups that are still pending in
the Firebase console on the team's agreed schedule. The backup covers absences
and conflicts of interest.

Before reviewing real applications, record the reviewers, check schedule, shared team
mailbox, private evidence location and deletion deadline in the team's private
handoff. Use the privacy contact as the shared mailbox only if the team
confirms it is suitable for association reviews. Keep correspondence and
evidence in that mailbox or the agreed private location, with access limited
to the reviewers who need it. Collect only what the decision requires and
delete the material by the agreed deadline. Keep applicant details out of
public issues and personal inboxes.

## Manual review

1. Match the pending `associations/{uid}` setup to the same UID in Firebase
   Authentication and `users/{uid}`. Confirm the email is verified and the
   account type is `association`. Hand the request to another owner if you
   belong to the applicant association.
2. Check the submitted name and type against an official EPFL directory or the
   association’s official website. Confirm that the applicant can represent the
   association through a committee contact found independently of the submission.
   Both EPFL and non-EPFL addresses can be valid. An email domain or a link
   supplied by the applicant is insufficient on its own.
3. Check for an existing account for the same association, including variants of
   its name. If an approved account already exists, keep the duplicate pending
   and direct the committee to recover the existing account. If two pending
   accounts claim the same association, pause both until the committee confirms
   which one should represent it. Refer ownership disputes to another project
   owner for review.
4. Approve a confirmed request using the [console procedure][console]. Set
   `users/{uid}.isAssociationVerified` to boolean `true`, then re-check the reviewed
   identity. If it changed, revoke approval and review again before notifying
   the applicant. The approved account opens its association tabs at the next
   start.
5. When evidence is insufficient or an application is rejected, the owner emails
   the verified signup address from the shared team mailbox with the reason
   and any next step. The flag stays absent or `false` and the app shows
   pending review. There is no stored rejection status.
   Rejected and duplicate accounts still have verified email addresses, so the
   planned cleanup for unverified accounts ([#226][unverified-cleanup]) would
   skip them. Associations cannot delete their own accounts in the app. The
   [association account deletion procedure][association-deletion] remains open.
6. To reapply, the applicant edits their permitted setup details if needed and
   replies to the review email with corrected evidence. A project owner reviews
   the request again against the same criteria. To revoke approval, use the
   [console procedure][console] to set the boolean to `false`. The next app start
   returns to pending review and new verified-only operations are denied.

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
- **Reject and reapply.** The committee cannot confirm the applicant’s authority.
  The owner sends the decision privately. The applicant can still edit their own
  setup, then submit corrected evidence for a fresh review.
- **Revoke.** The owner sets the flag to `false`. On the next start the account sees
  pending review. The association's own events and identity may remain in the
  Firestore cache. Existing public events remain visible to students and can
  still show the Verified badge, which comes from the event's
  `isAssociationEvent` field. Any later approval requires rechecking the name
  and type.

## Product backlog wording for the board owner

> Sprint 3 delivered manual Firebase-console verification via #135. #210 covers
> the manual review, rejection and reapplication policy. A future admin
> interface may list pending applications and support approval and rejection
> once the team has agreed its scope and server-side authorization. Refine and
> schedule that implementation after approval.

The board owner applies this wording to the product draft after merge.

[console]: ../README.md#verifying-an-association
[unverified-cleanup]: https://github.com/swent-polysocial/PolySocial/issues/226
[association-deletion]: ../CONTEXT.md#open-questions-dont-guess-ask
