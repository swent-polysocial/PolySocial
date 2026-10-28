<!-- Contributors: OpenAI Codex (proposed manual association review policy and examples, #210). -->

# Association review workflow — proposal for teammate agreement

Status: **proposed, not an agreed team policy**. The issue owner authorized drafting
this proposal on 28 October 2026. Teammates must review the examples and matrix
before adopting it or mirroring it to the wiki. This document does not authorize
production account changes, a new stored status, or wider pending-account access.

## Sprint 3 delivery and future scope

Sprint 3 delivers the [manual console procedure](../README.md#verifying-an-association)
(#135). The product backlog's eventual **Verify association accounts (admin)**
interface (pending list, approve/reject and reapply) remains future work. Its
implementation may be refined and scheduled only after team approval of scope,
authorization and the review policy. The human board owner updates that product
draft; this PR does not edit the board.

## Proposed manual review

- **Reviewer:** a PolySocial project owner. A committee member may supply evidence
  but cannot approve their own application or write the approval flag in the app.
- **Evidence:** independently check an official EPFL/association listing or official
  association website, the submitted name/type and a committee contact reachable
  through that official source. An email domain alone, or a link supplied by the
  applicant alone, is insufficient. Do not collect identity documents or publish
  personal contact details in GitHub. Keep only what the agreed operational
  process needs; no new database evidence fields are proposed.
- **Duplicates:** check for an existing verified association with the same real
  identity, including spelling variants. Pause the duplicate request and direct
  the committee to the existing account's recovery/contact process. Never copy
  the verified flag, share credentials, or change ownership without independent
  evidence and a separately approved account-transfer procedure.
- **Accept:** use #135's exact boolean operation and re-check the reviewed identity
  after writing. The account gets only its own association/event permissions.
- **Reject or request correction:** a project owner communicates manually through
  the verified contact channel, explains the reason and what correction/evidence
  is needed, and leaves approval absent/false. No rejection field, automated email
  sender or admin UI is added. This PR sends no applicant messages.
- **Reapply:** the applicant corrects their own permitted setup details and contacts
  the owner for another manual review. Pending setup editing depends on the
  separately approved #126/#127 access matrix. If that exception is not deployed,
  coordinate manually; do not widen rules as a workaround. Reapplication never
  grants app-data access before approval.

## Proposed eventual admin boundary

A future admin UI must use server-validated, explicitly assigned administrative
authority on a trusted backend. A hidden client button, email domain,
`accountType`, or client-writable role is never proof of admin authority. The
team must approve that backend design before implementation. Even an admin
screen must not let ordinary clients write `isAssociationVerified` directly.
There is no generic admin access rule proposed here.

| Actor / operation | Allow | Deny |
|---|---|---|
| Project owner, manual console | Review evidence; set approval boolean; revoke via #135 | Bypassing review or silently changing ownership |
| Student | Existing student permissions; safe verified identity under #126 | Approving/rejecting; private account data; pending identity |
| Association awaiting approval | Its own verified-email private account; own setup only after #126 exception is approved/deployed | Publishing; student data; other associations; approval writes |
| Approved association | Its own association and events under the existing rules | Other private accounts; student profiles, chats, groups, matching; approval writes |
| Rejected / correction requested | Same limited permissions as awaiting approval | Any extra app-data permission or client approval/status writes |
| Signed out / email unverified | No Firestore access granted by this proposal | Review operations and protected app data |

## Examples for teammate sign-off

1. **Approve:** an official listing and independently contacted committee member
   identify the same association. The owner sets the boolean true, re-checks
   name/type, and the next app start uses association routing. No student access.
2. **Duplicate:** a second account requests the same association. Approval remains
   false; the owner explains recovery for the existing account. No badge transfer.
3. **Reject → correct → reapply:** the official evidence does not establish the
   applicant's authority. The owner explains this manually. The applicant supplies
   corrected evidence and permitted details; only a new successful review can
   change approval. No stored rejection status is needed for this manual phase.
4. **Revoke:** the owner sets approval false. Verified-only server operations fail;
   #127 shows the pending state at the next app start. Name/type must be checked
   again before reapproval. Previously cached information is not remotely erased.

Open agreement points: confirm reviewers, sufficient evidence, duplicate recovery
ownership and the manual communication channel; review all allow/deny examples;
confirm #126/#127's pending exception independently. Record the agreed version in
CONTEXT.md and the wiki only after that review.
