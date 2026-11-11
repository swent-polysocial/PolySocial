<!-- Contributors: Mehdi (@mehdifddl), human author; OpenAI Codex drafted the test protocol and blank reporting templates for #240. No student sessions or findings are claimed. -->

# Student UX test · Sprint 5 · #240

**Status: prepared, not conducted.** Recruit 3–5 students outside the team, let them try
the same CI APK for a few days, then run the script below. The facilitator records the
tested commit, APK artifact/run link, dates and aggregate results. This document is ready
to copy to the project wiki; it contains no study results.

## Preparation

1. Download `polysocial-apk` from a successful `main` run, following the README's
   **Downloading the CI APK** instructions. Record the commit and artifact link below.
   A different build may need uninstalling the previous one because CI signing keys change.
2. Check installation, launch, Google/email sign-in and map rendering on the phone before
   distributing that build. Provide another sign-in method if that APK's signing certificate
   is not registered for Google sign-in. Never ask for a password or verification link.
3. Use clearly fictional demo events and groups. A team member may refresh demo data using
   the README; an agent must not seed production. Do not ask participants to send messages,
   create events or exercise features that the selected build does not support.
4. Explain participation is voluntary, they can stop or skip any task, and no recording is
   made. Tell them this prototype stores their account and interactions as described in
   **Privacy & data**. Do not promise deletion of group data before its cleanup is deployed.
   They may refuse approximate location and still participate. Ask them to avoid sharing
   personal information in free text.
5. Keep names, emails, student IDs, account identifiers, exact locations, screenshots of
   personal screens and raw recordings out of notes, the wiki and GitHub. Store only
   aggregate counts and paraphrased observations with identifying details removed.

## Introduction to read aloud

“We are testing PolySocial, not you. Please say what you expect to happen and what is
confusing. Try the app naturally for the next few days. You can skip anything or stop at
any time. We will keep anonymous, combined observations, without recording your screen
or collecting your account details.”

## Tasks · about 15 minutes

Give the goal first, without naming the button. Allow up to two minutes before offering
help. A task succeeds when the student reaches the stated outcome without assistance.
Record the aggregate number who completed it, needed help, or could not complete it.
Record an unavailable feature as **not tested**, rather than a participant failure.

| Goal to give the student | Outcome to observe |
|---|---|
| Open the app and reach upcoming events. | Entry completes or a recoverable error is understandable. |
| Find a sports event happening soon, then inspect its place and time. | Search/filter and event detail can be found; venue and date are understood. |
| Find that event on the map and open its details. | Marker, preview and Details form a discoverable path. |
| Find events within walking distance. You may keep location off. | Student finds distance controls and understands approximate, straight-line distance; refusing permission leaves browsing usable. |
| Choose an event to attend, then find it again. | In builds supporting registration/My events, registration and later retrieval are understandable. |
| Find people going to the same event. | In builds supporting groups, Find a group and the membership outcome are understandable. Do not require a chat before it ships. |
| Reopen a previously viewed event with the phone offline, then restore connectivity. | Cached content is available, or the missing-cache explanation and recovery are understandable. Do not change connectivity during sign-up. |
| Find what other students can see about you and how to ask for help about your data. | Privacy summary, full notice and current contact limitation can be found and understood. Do not ask the student to delete their real account. |

After each task ask: “What did you expect?” and “Was anything unclear?” At the end ask:
“Would this help you go to an event with other students? What would stop you using it?”
After the few-day trial ask what they tried, where they gave up, and which change would
help most. Paraphrase answers without names, event participation details or direct quotes.

## Aggregate report · fill only after sessions

- Tested commit and successful CI run/artifact: **not recorded**.
- Trial dates, session dates and number of students: **not conducted**.
- Device/Android versions tested (no device identifiers): **not recorded**.
- Supported features omitted from the script and why: **not recorded**.

| Task | Completed without help / attempted | Needed help | Could not complete | Observation without personal data |
|---|---|---|---|---|
| Fill after testing | — | — | — | No findings collected yet. |

## Findings and backlog decisions

For each finding, give a concrete trigger, the observed difficulty, its user impact and a
suggested acceptance criterion. The human Product Owner either creates a Product Backlog
item and records its link, or records a dismissal reason. Agents do not move board items.
Do not present proposed findings as observed ones, and do not publish raw participant notes.

| Finding | Evidence (aggregate) | Impact | Backlog link or dismissal reason |
|---|---|---|---|
| None collected | Not conducted | — | — |

## Scrum summary template

“Build [commit/artifact] was tried by [number] students outside the team between [dates].
We observed [aggregate outcomes]. [Number] findings became backlog items [links]; the
remaining findings were dismissed because [reasons]. Limitations: [untested features,
devices, trial duration].”

Replace placeholders only with collected evidence. Copy the protocol and completed report
to the wiki, and put the evidence-based summary into the Sprint 5 Scrum documents.
Until then, #240 remains incomplete.
