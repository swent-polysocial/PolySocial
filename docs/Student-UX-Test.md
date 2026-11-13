<!-- Contributors: Mehdi (@mehdifddl), human author, reported feedback from five external students; OpenAI Codex drafted the protocol and documented his aggregate report for #240. -->

# Student UX test · #240

**Status: feedback reported by Mehdi on 12 November 2026.** Five students outside the
team tried the version from the previous sprint. Mehdi reports that all five liked it
and raised no issues or suggestions. The aggregate report below records this feedback;
the protocol remains available for future sessions. Use of the full script and a
multi-day trial were not confirmed.

## Preparation

1. Download `polysocial-apk` from a successful `main` run, following the README's
   **Downloading the CI APK** instructions. Record the commit and artifact link below.
   Follow the current README's signing and installation guidance when switching builds.
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

## Aggregate report · reported 12 November 2026

- Source: Mehdi's report; no participant identities or raw notes are stored here.
- Participants: **5 students, all outside the team**.
- Tested version: **the previous sprint's version**, as reported by Mehdi; the exact
  commit and CI run/artifact were not recorded.
- Trial/session dates and duration: **not recorded**. The report date is not a session date.
- Overall feedback: **5/5 positive; no problems or suggestions reported**.
- Device/Android versions tested (no device identifiers): **not recorded**.
- Supported features omitted from the script and why: **not recorded**.

| Task | Completed without help / attempted | Needed help | Could not complete | Observation without personal data |
|---|---|---|---|---|
| Individual script tasks | Not recorded | Not recorded | Not recorded | No task-level outcomes were provided. |

This small, qualitative sample records overall feedback. It does not establish that every
script task succeeded, that every feature was exercised, or that the app has no defects.

## Findings and backlog decisions

For each finding, give a concrete trigger, the observed difficulty, its user impact and a
suggested acceptance criterion. The human Product Owner either creates a Product Backlog
item and records its link, or records a dismissal reason. Agents do not move board items.
Do not present proposed findings as observed ones, and do not publish raw participant notes.

| Finding | Evidence (aggregate) | Impact | Backlog link or dismissal reason |
|---|---|---|---|
| No actionable finding reported | Five external students liked the previous sprint's version and raised no issues, according to Mehdi. | No specific usability difficulty reported. | No backlog item created: no concrete change was requested or observed in the supplied feedback. |

## Scrum summary · ready to copy to Steve

Mehdi reported on 12 November 2026 that five students outside the team tried the previous
sprint's version of PolySocial. All five gave positive overall feedback and raised no
issues or suggestions. No actionable finding was reported, so no new backlog item was
created from this feedback. The exact build, session dates, duration, devices and
task-level outcomes were not recorded; no claim is made that every feature was tested.

Wiki: [protocol and report](https://github.com/swent-polysocial/PolySocial/wiki/Student-UX-Test).
Copy this summary into the appropriate Scrum document in Steve.
Publication in Steve must be confirmed by its human owner before
claiming that #240's Scrum-document criterion is complete.
