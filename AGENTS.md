<!-- Contributors: OpenAI Codex (initial draft); Claude (revised: definition of done, conventions, course rules, project context). A teammate should add their name here after reviewing. -->

# Instructions for coding agents

This repository is PolySocial, an EPFL SwEnt Android team project (team of 6,
public repository). Read this file, the README, the relevant issue, the
project wiki (team decisions live there), and nearby code before making
changes. The team owns every change, including code written with AI. Keep each
change small enough for a teammate to review and understand.

If anything here conflicts with an explicit instruction in the issue or from a
teammate, stop and ask (see "When blocked or unsure").

## Project context (fixed decisions)

- App: PolySocial. Insight: the barrier to campus life is social, not
  informational. Students (especially first-years and exchange students) skip
  events because they have nobody to go with.
- Core flow: a student browses upcoming events on an interactive Lausanne map,
  taps "find a group" on an event, and is matched with a small group of other
  students attending the same event (shared interests, common friends, section
  and year). The group gets a chat to plan the outing, checks in together at the
  venue via QR code, and shares photos in the event album.
- Students can also create private events (for example a study-together
  session). A private event must not appear on the map or be readable by
  non-members until matches are found and approved.
- Associations have their own accounts to create and manage their events.
- Stack: Kotlin, Jetpack Compose, Firestore, Firebase Authentication, Google
  Maps API. Every user authenticates with an EPFL account; enforce EPFL-only
  access on the server side (Security Rules), not only in the client.
- Sensors: camera (QR codes to register for events, check in, and add friends;
  photos for the shared album) and GPS (connect people by location, nearby
  events). NFC is not used; do not add it.
- Offline mode: cached map, registered events, event schedule, and reminders.
  Use Firestore's built-in offline persistence. Do not remove or weaken offline
  behavior.
- Plan limits: Firebase Authentication and Firestore run on the free Spark
  plan. Cloud Storage and Cloud Functions need the paid Blaze plan. Do not use
  Cloud Storage, Cloud Functions, or any paid service unless the team has
  explicitly decided to. If a feature needs one (for example event-album photo
  uploads), stop and ask.
- Do not add Supabase, a custom backend, a new sensor, or a new third-party
  service without team approval. A custom backend also needs the instructor's
  written approval.
- Users are students and their data is personal. Never log emails, names,
  locations, or photos, and never put real user data in tests or fixtures.

## Course rules that apply to every change

- Sprints last one week. A Sprint task is one GitHub issue, one branch, and
  normally one PR. A task may be split into several PRs (for example an
  interface-only PR so a teammate can build against it, or a polished smaller
  part when the task will not fit in the Sprint).
- Use the feature-branch workflow and push often. Tests for a feature go in the
  same PR as the feature, possibly in separate commits.
- Do not over-engineer. Solve the task at hand, and design it so it is easy to
  modify later. No speculative abstractions for hypothetical needs.
- Git history is used to assess the team's practices. Write meaningful commit
  messages, push small and regular commits, and avoid giant commits and PRs that
  bundle unrelated changes.
- Every PR gets at least one substantive teammate review, in English.
- AI use must always be acknowledged, or it counts as plagiarism. Humans must
  be able to explain and defend everything they submit. Write code and PR
  descriptions so the design choices are explained and easy to follow.
- Do not change course-required repository setup: branch protection, staff and
  coach access, SonarCloud configuration, or the Steve app installation.

## Architecture

- Follow MVVM: Compose screens display observable UI state and forward user
  actions to a ViewModel. ViewModels own screen state and coordinate
  operations. Models and repositories own domain data and data access.
- Inspect the actual package layout under `app/src/main/` and follow its
  structure, naming, navigation setup, and dependency versions. Do not assume a
  class, module, or Gradle task exists; check first.
- Put Firebase, Google Maps, and persistence code behind repository or service
  interfaces. Do not import a backend SDK in a ViewModel or a Composable. Inject
  dependencies so the data layer can be tested with fakes.
- Put device access (GPS, camera, QR decoding) behind interfaces too. Keep
  logic such as QR payload parsing and validation, group matching, and event
  filtering in plain Kotlin functions so it is unit-testable without a device.
- Keep long-running work off the main thread. Use coroutines and lifecycle-aware
  observable state (such as `StateFlow`). Represent loading, empty, success,
  and error states where relevant.
- Handle invalid input, network failures, denied permissions (location,
  camera), and loss of connectivity.
- Do not change generated files or Gradle wrapper binaries to make a build or
  test pass. Do not weaken existing tests.

## Backend and configuration

- The repository is public. Anything committed is world-readable, forever.
- Keep setup instructions in the README so every teammate can clone, configure,
  and run the app. Update it whenever setup changes.
- `google-services.json` and client API keys may be committed only if they hold
  client configuration. They identify the app; they do not authorize anything.
  Enforce access with Firestore Security Rules and test the rules for
  unauthorized access: a student must not read or write another student's
  private data, private events and group chats are readable only by their
  members, and only an association may manage its own events.
- Restrict the Google Maps key to the app's package name and SHA-1 fingerprint,
  and keep a budget alert on the Google Cloud project. Do not hardcode the key
  in Kotlin source. Read it through the Gradle Secrets plugin from
  `local.properties` (git-ignored), with a placeholder default so the project
  still builds without the key. If `local.properties` or the plugin does not
  exist yet, ask the team before inventing a scheme.
- Never put private keys, service-account JSON, signing credentials, or the
  Sonar token in the app or repository. Secrets belong in GitHub Actions
  secrets.
- Use the Firebase Emulator Suite or test doubles for backend tests. Never make
  tests depend on a live production backend.
- The CI workflow does not start Firebase emulators. Instrumented tests must
  therefore use fakes or in-memory repositories unless the workflow has been
  updated to start the emulators. Do not edit `.github/workflows/` without team
  approval.

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
  interacts with gets a `Modifier.testTag`. Public classes and functions get
  KDoc.
- No commented-out code, no `println` or debug logging left behind, and no TODO
  without an issue number.
- Do not add or upgrade dependencies unless the issue requires it. Use the
  existing version catalog.
- Follow the SonarCloud quality gate. Fix reported issues in code you touched
  instead of dismissing them.

## Build and test commands

Run from the repository root with the checked-in Gradle wrapper (on Windows
PowerShell use `./gradlew.bat`). These mirror what CI runs, in the same order:

```bash
./gradlew ktfmtFormat                  # fix formatting (local only)
./gradlew ktfmtCheck                   # CI: formatting check
./gradlew assemble lint                # CI: build and lint
./gradlew check                        # CI: unit tests and local checks
./gradlew connectedCheck               # CI: instrumented tests (emulator)
./gradlew jacocoTestReport             # CI: coverage report for Sonar
```

- `check` does not replace instrumented tests. Start an emulator or device
  before `connectedCheck`. CI uses an API 34 `google_apis` x86_64 emulator with
  no back camera, so tests must not depend on a real camera. Use fakes for
  camera and GPS.
- `./gradlew sonar` runs only in CI (it needs a secret token). Do not run it
  locally or try to obtain the token.
- JVM tests go under `app/src/test/`, device tests under `app/src/androidTest/`.
- If a command cannot run locally, report the command, the error, and which
  checks remain unverified. Never claim a check passed unless you ran it and it
  did.

## Writing tests

- Tests exist to verify functionality, not to raise the coverage number. Do not
  write tests only to increase coverage, and do not add assertion-free tests.
- Cover the main behavior, edge cases, and failure paths (empty input, invalid
  QR code, no network, denied permission, unauthorized access).
- Assert observable behavior (state, output, persisted data, rendered UI), not
  implementation details. A test should fail if the feature breaks.
- New logic and every bug fix ships with tests in the same PR. A bug fix
  includes a test that fails without the fix.
- Keep tests deterministic: no real time, network, or randomness without
  injection.
- Coverage is measured by Jacoco and reported in SonarCloud. New code should
  pass the Sonar quality gate, but a meaningful test always beats a coverage
  gain.

## Branches and commits

- Start from the current `main`. Work on one branch per issue or focused
  change, named `feature/<issue>-<slug>`, `fix/<issue>-<slug>`, or
  `chore/<issue>-<slug>` (for example `feature/42-event-map`).
- Never push directly to `main`. Never force-push a shared branch or rewrite
  pushed history. Do not squash or amend commits after they are pushed.
- Commit small, coherent steps and push the branch regularly, not once at the
  end. Do not open a PR only shortly before a Sprint Review.
- Commit subjects are in English, imperative, capitalized, at most 50
  characters, with no trailing period (for example `Add offline event cache`).
  Add a blank line and a body explaining why when the subject is not enough;
  wrap body lines around 72 characters. Reference the issue when applicable.
  Do not use lowercase Conventional Commit subjects.
- Keep commits under the human team member's existing Git identity. Do not
  change `user.name` or `user.email` to an AI identity, add an AI
  `Co-authored-by` or other contributor trailer, or add "generated by AI"
  signatures to commit messages. Acknowledge AI contributions in the affected
  files (see below), not in Git authorship.
- Do not use `--no-verify` or otherwise skip local hooks or checks.

## Pull requests

- One issue per PR. Never mix features, refactors, and formatting-only changes.
- Size: aim for a PR a teammate can review in about 30 minutes, roughly 500
  changed lines excluding generated files. This is a guideline, not a hard cap.
  If a PR exceeds about 1000 lines, it should likely be split into smaller PRs.
- The PR title follows the commit subject convention. The description states
  the issue (`Closes #N`), what changed and why, the design choices a reviewer
  should know about, the tests added, the commands run with results, and any
  setup step or known limitation. Note explicitly any check you could not run.
- Open a draft PR for work in progress or early feedback. Take it out of draft
  only when the definition of done below is met.

## Definition of done

**A change is ready for review (out of draft) only when all of these hold:**

1. It satisfies every acceptance criterion in the issue and does nothing
   outside the issue's scope.
2. It has meaningful tests for the new behavior, including at least one edge or
   failure case. Existing tests are unchanged unless the behavior was
   deliberately changed and the PR says why.
3. `ktfmtCheck`, `assemble lint`, `check`, and (for UI, Android, or backend
   changes) `connectedCheck` all pass locally, or the PR lists what could not
   be run.
4. The diff contains no secrets, generated files, stray files, debug code,
   commented-out code, or unexplained suppressions.
5. Errors, loading, empty states, denied permissions, and offline behavior are
   handled where the feature touches them, and no personal data is logged.
6. Privacy rules hold: anything touching events, groups, or chats has matching
   Security Rules and a test for unauthorized access.
7. Contributor comments are added or updated at the top of every file that AI
   or an external source contributed to.
8. The README is updated if setup, configuration, or run steps changed.
9. The PR description is complete as described above.

**A PR may be merged only when all of these hold:**

1. The definition of done above still holds on the latest commit.
2. CI is green on the latest commit (formatting, build, lint, unit tests,
   instrumented tests, and the Sonar analysis).
3. The branch is up to date with `main` and has no conflicts.
4. At least one teammate (other than the author) has approved after reading
   and understanding the diff, and all review threads are resolved.
5. The human author merges with a **merge commit** (not squash), so the
   individual commits stay in the history, then deletes the branch and closes
   the issue.
6. The human author updates the board item: moved to `Done in Si` for the
   current Sprint, with `Actual Time` filled in.

## Reviewing code (when asked to review a PR)

- An AI review can help, but it does not replace a teammate's approval, and it
  must be acknowledged.
- Read the entire diff. Discuss both design and implementation. Look for bugs,
  integration issues, missing tests, error handling, and privacy problems.
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
- Agents do not edit the board. The human owner moves the item to
  `In Development` when work starts and to `In Review` when the PR leaves draft.

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

- Merge, approve, or dismiss a review on your own PR. The team merges.
- Push to `main`, force-push a shared branch, or rewrite pushed history.
- Use `--no-verify`, skip or delete tests, weaken assertions, or disable CI
  steps or lint rules to get green.
- Modify `.github/workflows/`, Firestore Security Rules, authentication logic,
  or repository settings without team approval and a note in the PR.
- Commit secrets, service-account files, or the Sonar token.
- Use paid Firebase services (Cloud Storage, Cloud Functions) without a team
  decision.
- Claim a check passed without running it.
- Guess at an unspecified product or interface decision.

## When blocked or unsure

Stop. Do not choose an interpretation silently. State the question, the options
you see, and your recommendation in the issue, the PR, or a message to the
human, and wait for an answer. This covers unclear acceptance criteria, missing
Gradle tasks or config, conflicting instructions, and any change that would
touch the "Never do" list.

## Agent workflow

1. Restate the issue's goal and acceptance criteria. Inspect the smallest
   relevant part of the codebase and the existing tests.
2. Implement one reviewable change consistent with the current architecture and
   the conventions above, with tests in the same change.
3. Run `ktfmtFormat`, then the commands in "Build and test commands". Inspect
   failures and fix their causes.
4. Check the result against the definition of done, including a review of the
   full diff for accidental files, leaked credentials, missing contributor
   comments, error handling, and real test assertions.
5. Open or update the PR with the required description. Leave code review,
   approval, and the merge decision to the team.
