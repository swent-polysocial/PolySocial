<!-- Contributors: OpenAI Codex (initial draft; approved Mapbox provider/token policy for #50); Claude (revised: definition of done, conventions, course rules, project context, Security Rules process, team-agreement reconciliation, session context, secrets check, test and PR-size rules; association accounts without an EPFL email). -->

# Instructions for coding agents

This repository is PolySocial, an EPFL SwEnt Android team project (team of 6,
public repository). At the start of every session, read this file and
`CONTEXT.md` (see "Session context"). Before making changes, also read the
README, the relevant issue, the project wiki (in particular the Team
Agreement, where team process decisions live), and nearby code. The team owns every change,
including code written with AI. Keep each change small enough for a teammate
to review and understand.

If anything here conflicts with an explicit instruction in the issue or from a
teammate, stop and ask (see "When blocked or unsure"). If this file and the
wiki's Team Agreement ever disagree, the Team Agreement is the source of truth
for process, and this file should be updated to match — flag the mismatch
instead of silently picking one.

## Session context (`CONTEXT.md`)

`CONTEXT.md` at the repository root is the team's short, current record of
what an agent can't easily get from the code: the current state of the
codebase and tooling, the architectural and design decisions in force
(including ones the issues don't reflect yet), known traps, open questions,
and a one-line decision log. It lets every session start from the team's
latest decisions instead of re-deriving them from code, issues, and chat, or
guessing. It also shows how the design evolved.

- Read it at the start of every session, after this file.
- Update it **in the same branch/PR** whenever the change makes or changes an
  important decision (architecture, data model, Security Rules, dependencies,
  setup, or process), discovers a non-obvious trap, or resolves or raises an
  open question. Edit "Current state" and "Decided design" in place, and add
  one dated line, with a link, to the top of the decision log.
- Keep it concise. Do not log routine progress, task status, or anything the
  code, an issue, or this file already says clearly. Point to it instead.
  Remove log lines that are fully reflected above and older than a Sprint;
  git history keeps them.
- If `CONTEXT.md` disagrees with the code, an issue, or a teammate, it may be
  stale: flag the mismatch and ask instead of silently picking one. For
  process, this file and the Team Agreement win.
- If `CONTEXT.md` conflicts while rebasing onto `main`, keep both sides'
  entries.

## Project context (fixed decisions)

- App: PolySocial. Insight: the barrier to campus life is social, not
  informational. Students (especially first-years and exchange students) skip
  events because they have nobody to go with.
- Core flow: a student browses upcoming events on an interactive Lausanne map,
  taps "find a group" on an event, and is matched with a small group of other
  students attending the same event (shared interests, common friends, section
  and year). The group gets a chat to plan the outing, checks in together at the
  venue via QR code, and shares photos in the event album.
- Group matching: implement it as a deterministic heuristic (for example
  first-fit / tag overlap) in the early Sprints rather than an optimal solver.
  Keep the algorithm in a pure, framework-free module with no Android or
  Firebase dependency, so it is fully unit-testable. It may later move
  server-side (a Cloud Function or a dedicated background repository worker)
  as it matures — do not build that until an issue asks for it.
- Students can also create private events (for example a study-together
  session). A private event never appears on the public map. It is readable
  only by its creator, its members, and students whose match request the
  creator has approved. Nobody else can read it at any time.
- Anyone can create an event and becomes its organizer; an event can have one
  or several student organizers, and only its organizers can manage it.
  Associations have their own accounts, usually with a non-EPFL email (see
  "Authentication"). Only an association that a PolySocial admin has verified
  may publish an event under its name (with a verified badge); do not let a
  newly created association account act as verified by default.
- Profiles are split: `users/{uid}` is private to its owner, and the fields
  other students may see live in a separate public profile. Students choose a
  public or private profile (Instagram-style); section, year, and interests
  are always visible because matching needs them.
- Content moderation: chats, photo uploads, and association accounts can be
  misused. Design Security Rules and any Cloud Functions so that report/block
  and rate-limiting features can be added without a rule rewrite (for example,
  do not assume every authenticated user may write unlimited messages), but
  only implement the enforcement itself when its own issue is scheduled.
- Privacy: students' profiles, photos, chat messages, and locations are
  personal data under the Swiss nLPD. Collect only what a feature actually
  needs, support account and data deletion, and do not implement continuous or
  precise location tracking beyond what the user explicitly shares (GPS use is
  limited to features like "nearby events" and one-off location checks, not a
  location history). Keep the in-app privacy notice current when a feature
  changes what is collected.
- Stack: Kotlin, Jetpack Compose, Firestore, Firebase Authentication, Mapbox
  Maps SDK. Nominatim/OpenStreetMap is approved for geocoding, reverse
  geocoding, and search (not autocomplete/search-as-you-type, which its usage
  policy forbids): send a custom User-Agent, respect the 1 request/second
  limit, debounce and cache queries, and show OSM attribution in the UI. Cloud
  Storage is approved for event/profile photos: compress and resize images
  client-side before upload, enforce file size and type limits in Storage
  Security Rules, and cache thumbnails. Firebase Cloud Messaging is not yet in
  use; evaluate it only once notifications enter the backlog, with an in-app
  notification list as the fallback.
- Authentication: every student signs in with an EPFL account. Associations
  have no EPFL email, so they sign in with their own email address. Enforce
  this in Firestore Security Rules, not only in the client. Every rule that
  grants access requires `request.auth != null` and
  `request.auth.token.email_verified == true`, plus one of:
  - an email matching `@epfl.ch` (a student);
  - a verified association account (`accountType == "association"` and
    `isAssociationVerified`, set by hand in the Firebase console by a
    PolySocial admin), and only for what an association does: set up its
    association and see its profile, manage its members and their roles, and
    publish and manage its own events (upcoming, drafts, past). It gets no
    access to students' profiles, groups, chats or matching.

  The only exception: any account with a verified email may read and write
  its own `users/{uid}`, but may never set `isAssociationVerified`, on create
  or on update. That is all an unverified association can reach. Never add the
  verified-association branch to a rule while a broader rule (such as the
  catch-all `match /{document=**}`) still lets other users write
  `users/{uid}`; protect the flag first. If the sign-in provider does not
  guarantee these claims, stop and ask before designing a workaround.
- Sensors: camera (QR codes to register for events, check in, and add friends;
  photos for the shared album) and GPS (connect people by location, nearby
  events, subject to the privacy limit above). NFC is not used; do not add it.
- Offline mode means: cached event data, registered events, event schedule,
  and reminders remain available without a network, using Firestore's built-in
  offline persistence. The map shows cached events on whatever tiles the Maps
  SDK has already cached. Do not implement map tile prefetching or explicit
  offline region downloads; this remains outside the team-approved offline
  scope. Do not remove or weaken offline behavior.
- Plan: Firebase Authentication, Firestore, Cloud Storage, and Cloud Functions
  run on the Blaze (pay-as-you-go) plan, with a budget alert configured on the
  Google Cloud project (for example around 5 CHF/month). Expected usage should
  stay within the free quotas; a feature that would clearly exceed them needs a
  team decision first.
- Do not add Supabase, a custom backend, a new sensor, or a third-party service
  beyond the ones listed above without team approval. A custom backend also
  needs the instructor's written approval.
- Users are students and their data is personal. Never log emails, names,
  locations, or photos, and never put real user data in tests or fixtures.

## Course rules that apply to every change

- Sprints last one week, Friday to Friday. A Sprint task is one GitHub issue,
  one branch, and normally one PR. A task may be split into several PRs (for
  example an interface-only PR so a teammate can build against it, or a
  polished smaller part when the task will not fit in the Sprint).
- A task is only pulled into a Sprint if it meets the team's Definition of
  Ready: a clear description and acceptance criteria, an estimate, and small
  enough to finish within one Sprint (see "Scrum board").
- Tests for a feature (including fakes and test helpers) go in the same PR as
  the feature, possibly in separate commits. A bug fix and its regression test
  go in the same commit.
- Do not over-engineer. Solve the task at hand, and design it so it is easy to
  modify later. No speculative abstractions for hypothetical needs.
- Git history is used to assess the team's practices. Write meaningful commit
  messages, push small and regular commits, and avoid giant commits and PRs that
  bundle unrelated changes.
- Every PR gets at least one substantive teammate review, in English.
- AI use must always be acknowledged, or it counts as plagiarism (see
  "Acknowledging AI and other sources"). Write code and PR descriptions so the
  design choices are explained and easy to follow.
- Do not change course-required repository setup: branch protection, staff and
  coach access, SonarCloud configuration, or the Steve app installation.

## Architecture

- Follow MVVM: Compose screens display observable UI state and forward user
  actions to a ViewModel. ViewModels own screen state and coordinate
  operations. Models and repositories own domain data and data access. No
  business logic or direct data access (Firestore, HTTP, location, camera) in
  a Composable — a Composable talks to a ViewModel, a ViewModel talks to a
  repository.
- Inspect the actual package layout under `app/src/main/` and follow its
  structure, naming, navigation setup, and dependency versions. Do not assume a
  class, module, or Gradle task exists; check first.
- Put Firebase, Mapbox, Nominatim, and persistence code behind repository
  or service interfaces. Do not import a backend SDK in a ViewModel or a
  Composable. Inject dependencies so the data layer can be tested with fakes.
- Put device access (GPS, camera, QR decoding) behind interfaces too. Keep
  logic such as QR payload parsing and validation, group matching (see
  "Project context"), and event filtering in plain Kotlin functions so it is
  unit-testable without a device.
- Keep long-running work off the main thread. Use coroutines and lifecycle-aware
  observable state (such as `StateFlow`). Represent loading, empty, success,
  and error states where relevant.
- Handle invalid input, network failures, denied permissions (location,
  camera), and loss of connectivity, and surface the failure in UI state rather
  than swallowing it silently.
- Do not change generated files or Gradle wrapper binaries to make a build or
  test pass. Do not weaken existing tests.

## Backend and configuration

- The repository is public. Anything committed is world-readable, forever.
- Keep setup instructions in the README so every teammate can clone, configure,
  and run the app. Update it whenever setup changes.
- `app/google-services.json` and `local.properties` are git-ignored and never
  committed: each teammate gets them as the README's Setup section describes,
  and CI creates them from GitHub Actions secrets. Client keys identify the app
  and do not authorize anything (access control lives in Firestore Security
  Rules, see next section), but we still keep them out of the public repo.
- **Secrets check, every session and every PR.** At the start of a session,
  audit the remote for anything that should be private: list every file ever
  committed (`git log --all --name-only`) and look for `google-services.json`,
  `local.properties`, `secrets.properties`, keystores (`*.jks`, `*.keystore`,
  `*.p12`), `.env` files and service-account JSON, then search for key patterns
  such as `AIza…` and `-----BEGIN … PRIVATE KEY`. Before every commit, read
  `git status` and confirm each sensitive file is covered with
  `git check-ignore -v <file>`. Extend `.gitignore` first if one is not
  covered. Never stage with `git add -A` or `git add .` without reviewing what
  it includes. If a secret is already public, stop and tell the human: the key
  must be rotated, and deleting the file does not remove it from history.
- Mapbox is approved in #50, replacing Google Maps. The account's signup
  public `pk.` token is approved for initial development; one token can serve
  the prototype's local builds. Before production, use a dedicated public
  token with only `styles:read` and `fonts:read`, stored as
  `MAPBOX_ACCESS_TOKEN` in git-ignored `local.properties`. Gradle generates the
  SDK string resource, with an empty-token setup state so CI can build. Never
  package a secret `sk.` token. Mapbox mobile tokens do not support Google's
  package/SHA-1 restrictions; do not use browser URL restrictions for Android.
  Monitor usage and retain the team's budget alerts. Keep logo, attribution
  and telemetry opt-out available; do not enable continuous GPS tracking.
- Never put private keys, service-account JSON, signing credentials, or the
  Sonar token in the app or repository. Secrets belong in GitHub Actions
  secrets.
- No live network calls in automated tests. Test Nominatim and any other
  external HTTP client with canned JSON responses or MockWebServer. Test
  Firestore, Firebase Auth, and Cloud Storage against the Firebase Local
  Emulator Suite, never a live production backend — locally always, and in CI
  once the workflow starts the emulators (a team decision; see "Firestore
  Security Rules" for what to do until then).
- Do not edit `.github/workflows/` without team approval.

## Firestore Security Rules

- Rules must guarantee at least: a student cannot read or write another
  student's private data; private events and group chats are readable only by
  their members (see "Project context"); only an event's organizers can
  manage it, and only a verified association can publish an event under its
  name; every access requires a verified EPFL account, or a verified
  association account limited to its own association and events, except
  each account's own `users/{uid}` (see "Authentication").
- An agent may change the rules file when the issue requires it (for example a
  new collection or a new access pattern). Every such PR must:
  1. say "Changes Security Rules" in the PR description, with a short
     explanation of what access is granted or removed and why;
  2. include rules tests covering at least one allowed access and one denied
     access for each rule added or changed;
  3. request a reviewer's explicit sign-off on the rules change.
- Never loosen a rule (for example widen a match or remove a condition) to make
  a feature or test work. If a feature seems to need broader access, stop and
  ask.
- Run rules tests locally against the Firestore emulator before marking the PR
  ready, with the command documented in the README (for example
  `firebase emulators:exec --only firestore,auth "<rules test command>"`), and
  paste the result in the PR. If the README does not document a rules test
  command yet, stop and ask; do not substitute fake-based tests, which cannot
  verify rules.
- The team intends CI to also run Firestore/Auth/Storage tests against the
  emulator suite. If `.github/workflows/` does not yet start the emulators,
  treat that as a known setup gap: note it in the PR rather than silently
  relying only on local runs, and keep using fakes or in-memory repositories
  for the parts of the suite CI does run.

## Code conventions

- Formatting is enforced by ktfmt. Before every commit run
  `./gradlew ktfmtFormat` (if the task is missing, ask; do not hand-format
  around it), then verify with `./gradlew ktfmtCheck`.
- Fix lint findings; do not hide them. `@Suppress`, `@Ignore`, lint baselines,
  and disabled lint rules need a one-line justification in the code and a
  reviewer's agreement in the PR. Check whether a lint config or baseline
  already exists before touching either; never create a baseline to make lint
  pass.
- User-facing strings go in `strings.xml`. Every Composable that a UI test
  interacts with gets a `Modifier.testTag`.
- Write KDoc for repository and service interfaces, ViewModels, and any
  non-obvious logic (matching, QR parsing, filtering). Simple Composables and
  self-explanatory functions do not need it.
- No commented-out code, no `println` or debug logging left behind, and no TODO
  without an issue number.
- Do not add or upgrade dependencies unless the issue requires it. Use the
  existing version catalog.
- Follow the SonarCloud quality gate. Fix reported issues in code you touched
  instead of dismissing them.

## Build and test commands

Run from the repository root with the checked-in Gradle wrapper (on Windows
PowerShell use `./gradlew.bat`). The commands marked `CI` below are the steps
the CI workflow runs at the time of writing. The workflow is expected to
change (for example to add emulator support), so treat `.github/workflows/`
as the source of truth and flag any mismatch with this list in the PR:

```bash
./gradlew ktfmtFormat                  # fix formatting (local only)
./gradlew ktfmtCheck                   # CI: formatting check
./gradlew assembleDebug                # CI: debug build
./gradlew lint                         # CI: lint
./gradlew testDebugUnitTest            # CI: unit tests
./gradlew connectedCheck               # CI: instrumented tests (emulator)
./gradlew jacocoTestReport             # CI: coverage report for Sonar
./gradlew assembleRelease              # CI: release build (separate job)
```

- Locally, `./gradlew assemble lint` builds both variants and runs lint, and
  `./gradlew check` runs `ktfmtCheck`, `lint`, and `testDebugUnitTest`.
  Together they run every CI check above except the instrumented tests.
- CI skips the build and tests for PRs that only change Markdown files or
  `docs/`. Every other PR runs all the steps above.
- Rules tests: see "Firestore Security Rules" (local always; emulator
  required).
- `check` does not replace instrumented tests. Start an Android emulator or
  device before `connectedCheck`. Check the workflow for the CI emulator's API
  level and image; CI emulators have no real camera, so tests must not depend
  on one. Use fakes for camera and GPS.
- `./gradlew sonar` runs only in CI (it needs a secret token). Do not run it
  locally or try to obtain the token.
- JVM tests go under `app/src/test/`, device tests under `app/src/androidTest/`.
- If a command cannot run locally, report the command, the error, and which
  checks remain unverified. Never claim a check passed unless you ran it and it
  did.

## Writing tests

- Tests exist to verify functionality, not to raise the coverage number. Do not
  write tests only to increase coverage, and do not add assertion-free or
  empty tests.
- Cover the main behavior, edge cases, and failure paths (empty input, invalid
  QR code, no network, denied permission, unauthorized access).
- Assert observable behavior (state, output, persisted data, rendered UI), not
  implementation details. A test should fail if the feature breaks.
- New logic and every bug fix ships with tests in the same PR. A bug fix
  includes a test that fails without the fix.
- Keep tests deterministic: no real time, no live network calls (see
  "Backend and configuration"), and no unseeded randomness.
- Coverage, as reported by JaCoCo/SonarCloud in CI: at least 95% line coverage
  and 65% branch coverage on new code, and overall project coverage must not
  decrease. A meaningful test always beats a coverage gain — do not pad
  coverage with tests that don't assert real behavior.

## Branches and commits

- Start from the current `main`. Work on one branch per issue, named after the
  issue it closes:

  | Type | Pattern | Example |
  |---|---|---|
  | Feature | `feature/<issue>-<slug>` | `feature/42-event-map` |
  | Fix | `fix/<issue>-<slug>` | `fix/57-login-crash` |
  | Refactor | `refactor/<issue>-<slug>` | `refactor/61-repo-layer` |
  | Tests | `test/<issue>-<slug>` | `test/63-matching-tests` |
  | Chore / CI / docs | `chore/<issue>-<slug>` | `chore/12-ci-cache` |

  Documentation-only changes (see "Scrum board") have no issue, so their
  branch is `chore/<slug>`, for example `chore/update-agents-md`.
- Commit small, coherent steps and push the branch regularly, not once at the
  end. Do not open a PR only shortly before a Sprint Review.
- Use Conventional Commit subjects: `<type>: <summary>` or
  `<type>(<scope>): <summary>`, in English, imperative, lowercase after the
  colon, at most 50 characters in total, with no trailing period. Types:
  - `feat`: a new user-facing feature
  - `fix`: a bug fix
  - `test`: adding or correcting tests only
  - `refactor`: a code change that neither adds a feature nor fixes a bug
  - `docs`: documentation only (README, KDoc, this file)
  - `style`: formatting only (for example a `ktfmtFormat` run)
  - `build`: Gradle, the version catalog, or dependencies
  - `ci`: `.github/workflows/`
  - `chore`: other maintenance that fits none of the above

  Examples: `feat: add offline event cache`,
  `fix(map): keep markers after rotation`, `test: cover invalid QR payloads`.
- Add a blank line and a body whenever the subject is not enough on its own.
  The body explains what changed in behavior and why, not which files were
  touched (the diff already shows that). Wrap body lines around 72 characters
  and reference the issue when applicable (for example `Refs #42`).
- When a commit is genuinely co-written with a teammate (for example paired
  programming), add one `Co-authored-by: Name <email>` trailer per co-author.
  Keep commits under the human authors' own Git identities: do not change
  `user.name` or `user.email` to an AI identity, and never add an AI
  `Co-authored-by` trailer or a "generated by AI" signature. AI contributions
  are acknowledged in file headers instead (see "Acknowledging AI and other
  sources").
- Do not squash or amend commits after they are pushed, except when rebasing
  onto `main` to resolve a conflict (below). Force-pushing is allowed only on
  your own feature branch, using `git push --force-with-lease`. Never
  force-push `main` or a branch other people are also pushing to.
- **Merge conflicts:** if your branch conflicts with `main`, rebase onto `main`
  locally and resolve the conflicts there — do not merge `main` into your
  branch as a substitute. If resolving a conflict changes logic someone else
  wrote, ping them for a quick check before pushing the rebased branch.

## Pull requests

- One issue per PR. Never mix features, refactors, and formatting-only changes.
- Size: at most about 1000 changed lines of **production code**
  (`app/src/main` and build files), as in the wiki's review checklist. Tests,
  fakes, fixtures, resources, and generated files don't count, so tests are
  never trimmed to fit the cap; reviewers still read the tests in full. This
  is a cap, not a target: aim for a PR a teammate can review in about 30
  minutes, and split larger work into several PRs. Even under the cap, split a
  PR along a natural seam when it does two things.
- Every PR has an assignee (the author).
- The PR title follows the commit subject convention. The description states
  the issue (`Closes #N`, or "No linked issue (docs-only)" for a
  documentation-only change), what changed and why, the design choices a
  reviewer should know about, the tests added, the commands run with results, any
  Security Rules change, and any setup step or known limitation. Note
  explicitly any check you could not run.
- Open a draft PR for work in progress or early feedback. Take it out of draft
  only when the definition of done below is met.
- After a PR is opened, respond to review comments within 24 hours on working
  days. If new commits are pushed after a reviewer has approved, the reviewer
  must re-approve before merge — do not treat the earlier approval as still
  valid.

## Definition of done

**A change is ready for review (out of draft) only when all of these hold:**

1. It satisfies every acceptance criterion in the issue and does nothing
   outside the issue's scope.
2. It has meaningful tests for the new behavior, including at least one edge or
   failure case, meeting the coverage bar in "Writing tests." Existing tests
   are unchanged unless the behavior was deliberately changed and the PR says
   why.
3. `ktfmtCheck`, `assemble lint`, `check`, and (for UI, Android, or backend
   changes) `connectedCheck` all pass locally, or the PR lists what could not
   be run.
4. The diff contains no secrets, generated files, stray files, debug code,
   commented-out code, or unexplained suppressions, and `.gitignore` covers
   every sensitive file in the working tree (see the secrets check in
   "Backend and configuration").
5. Errors, loading, empty states, denied permissions, and offline behavior are
   handled where the feature touches them, and no personal data is logged.
6. If the change adds or changes data access for events, groups, chats, or
   user data, the Security Rules cover it and the process in "Firestore
   Security Rules" is followed, including local rules test results in the PR.
7. Contributor comments are added or updated at the top of every file that AI
   or an external source contributed to.
8. The README is updated if setup, configuration, or run steps changed.
9. `CONTEXT.md` is updated if the change makes or changes an important
   decision (see "Session context").
10. The PR description is complete as described above.

**Merging is done by the human author, only when:** the definition of done
still holds on the latest commit; CI is green on the latest commit; the branch
is up to date with `main`; at least one other teammate has approved the
latest commit (a new commit after approval requires re-approval) after
reading the diff, with explicit sign-off on any Security Rules change; and all
review threads are resolved. Merge with GitHub's **rebase merge**, so `main`
stays linear and keeps each PR's individual commits (the Git history the
course assesses), which is why every commit must be small and meaningful on
its own. Board updates, branch deletion, and closing the issue
are also the human author's job.

## Reviewing code (when asked to review a PR)

- An AI review can help, but it does not replace a teammate's approval, and it
  must be acknowledged.
- Read the entire diff. Discuss both design and implementation. Look for bugs,
  integration issues, missing tests, error handling, and privacy problems.
  Check every Security Rules change against the access guarantees above.
- Write in English. Talk about the code, not the author. Ask when something is
  unclear, and include positive comments where deserved.
- Prefix comments consistently with one word: `Important`, `Nitpick`, or
  `Question`.
- Give a bare "LGTM" only for a tiny, obviously correct change.
- The commit messages and the code together must explain a change well enough
  to be understood months later. Flag when they do not.

## Scrum board

- Every change maps to an issue on the PolySocial `Scrum Board`. Do not start
  work without an issue. If none exists, ask.
- Exception: documentation-only changes do not need an issue. This covers
  Markdown docs such as this file or the README, with no change to code, build
  files, configuration, workflows, or Security Rules. They still go through a
  branch and a reviewed PR like any other change.
- A task can only be pulled into a Sprint once it meets the Definition of
  Ready: a clear description and acceptance criteria, an estimate, and small
  enough to finish within one Sprint. Estimation uses story points (1, 2, 3, 5,
  8) agreed at Sprint Planning, alongside the board's Estimated Time
  (person-hours) field; anything estimated at 8 points should be split before
  it is pulled in.
- Agents do not edit the board; the human owner moves items.

## Acknowledging AI and other sources

- When AI or another external source contributes to a source file, add or
  update a comment at the top of that file naming the contributor and the
  contribution. Keep it short and truthful, for example:

  ```kotlin
  // Contributors: OpenAI Codex (drafted repository tests); Jane Doe (reviewed and revised).
  ```

- For Markdown or configuration files that allow comments, use the appropriate
  top-of-file comment. If the format does not allow comments, document the
  contribution in the PR and in another tracked file. Never invent human
  reviews or claim that AI output was written independently.
- Human contributors must read and understand every submitted change and be
  able to explain its design, behavior, tests, and review comments to their
  coaches.

## Never do

- Merge, approve, or dismiss a review on your own PR.
- Push to `main`, force-push `main`, or force-push a branch other people are
  also pushing to. (Force-pushing your own feature branch with
  `--force-with-lease` to rebase onto `main` is fine — see "Branches and
  commits.")
- Use `--no-verify`, skip or delete tests, weaken assertions, or disable CI
  steps or lint rules to get green.
- Loosen a Security Rule, or change rules without following the process in
  "Firestore Security Rules."
- Modify `.github/workflows/`, authentication logic, or repository settings
  without team approval and a note in the PR.
- Commit secrets, service-account files, or the Sonar token.
- Add a paid or third-party service beyond the ones approved in "Project
  context" without a team decision.
- Implement map tile prefetching or offline tile storage.
- Implement continuous or precise location tracking beyond what "Project
  context" allows.
- Claim a check passed without running it.
- Guess at an unspecified product or interface decision.

## When blocked or unsure

Stop. Do not choose an interpretation silently. State the question, the options
you see, and your recommendation in the issue, the PR, or a message to the
human, and wait for an answer. This covers unclear acceptance criteria, missing
Gradle tasks, config, or rules test commands, conflicting instructions, and any
change that would touch the "Never do" list.

## Agent workflow

1. Read `CONTEXT.md` and run the secrets check on the remote (see "Backend and
   configuration"). Restate the issue's goal and acceptance criteria.
   Inspect the smallest relevant part of the codebase and the existing tests.
2. Implement one reviewable change consistent with the current architecture and
   the conventions above, with tests in the same change.
3. Run `ktfmtFormat`, then the commands in "Build and test commands" (plus the
   rules tests if Security Rules changed). Inspect failures and fix their
   causes.
4. Check the result against the definition of done, including a review of the
   full diff for accidental files, leaked credentials, missing contributor
   comments, error handling, and real test assertions. Update `CONTEXT.md` if
   the change made an important decision.
5. Open or update the PR with the required description. Leave code review,
   approval, and the merge decision to the team.
