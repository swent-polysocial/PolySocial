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
  session). A private event never appears on the public map. It is readable
  only by its creator, its members, and students whose match request the
  creator has approved. Nobody else can read it at any time.
- Associations have their own accounts to create and manage their events.
- Stack: Kotlin, Jetpack Compose, Firestore, Firebase Authentication, Google
  Maps API.
- Authentication: every user signs in with an EPFL account. Enforce this in
  Firestore Security Rules, not only in the client: every rule that grants
  access requires `request.auth != null`,
  `request.auth.token.email_verified == true`, and an email matching
  `@epfl.ch`. If the sign-in provider does not guarantee these claims, stop and
  ask before designing a workaround.
- Sensors: camera (QR codes to register for events, check in, and add friends;
  photos for the shared album) and GPS (connect people by location, nearby
  events). NFC is not used; do not add it.
- Offline mode means: cached event data, registered events, event schedule,
  and reminders remain available without a network, using Firestore's built-in
  offline persistence. The map shows cached events on whatever tiles the Maps
  SDK has already cached. Do not implement map tile prefetching or offline tile
  storage; the Maps SDK does not support it and the Google Maps terms restrict
  it. Do not remove or weaken offline behavior.
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
- Tests for a feature go in the same PR as the feature, possibly in separate
  commits.
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
  Access control lives in Firestore Security Rules (see next section).
- Restrict the Google Maps key to the app's package name and the SHA-1
  fingerprints of every keystore that builds the app: each teammate's debug
  keystore, the CI keystore, and the release keystore. A missing fingerprint
  shows up as a blank map; the fix is to register the fingerprint, never to
  loosen or remove the restriction. Keep a budget alert on the Google Cloud
  project.
- Do not hardcode the Maps key in Kotlin source. Read it through the Gradle
  Secrets plugin from `local.properties` (git-ignored), with a placeholder
  default so the project still builds without the key. If `local.properties` or
  the plugin does not exist yet, ask the team before inventing a scheme.
- Never put private keys, service-account JSON, signing credentials, or the
  Sonar token in the app or repository. Secrets belong in GitHub Actions
  secrets.
- Use the Firebase Emulator Suite or test doubles for backend tests. Never make
  tests depend on a live production backend.
- Do not edit `.github/workflows/` without team approval.

## Firestore Security Rules

- Rules must guarantee at least: a student cannot read or write another
  student's private data; private events and group chats are readable only by
  their members (see "Project context"); only an association can manage its
  own events; every access requires a verified EPFL account.
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
- Rules tests run against the Firestore emulator, which CI does not start. Run
  them locally before marking the PR ready, with the command documented in the
  README (for example
  `firebase emulators:exec --only firestore,auth "<rules test command>"`), and
  paste the result in the PR. If the README does not document a rules test
  command yet, stop and ask; do not substitute fake-based tests, which cannot
  verify rules.
- Instrumented tests that run in CI must use fakes or in-memory repositories,
  since CI has no emulators, unless the team updates the workflow.

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
PowerShell use `./gradlew.bat`). These mirror what CI runs at the time of
writing. The workflow is expected to change substantially, so treat
`.github/workflows/` as the source of truth and flag any mismatch with this
list in the PR:

```bash
./gradlew ktfmtFormat                  # fix formatting (local only)
./gradlew ktfmtCheck                   # CI: formatting check
./gradlew assemble lint                # CI: build and lint
./gradlew check                        # CI: unit tests and local checks
./gradlew connectedCheck               # CI: instrumented tests (emulator)
./gradlew jacocoTestReport             # CI: coverage report for Sonar
```

- Rules tests: see "Firestore Security Rules" (local only, emulator required).
- `check` does not replace instrumented tests. Start an emulator or device
  before `connectedCheck`. Check the workflow for the CI emulator's API level
  and image; CI emulators have no real camera, so tests must not depend on one. Use fakes for
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
- Keep commits under the human team member's existing Git identity. Do not
  change `user.name` or `user.email` to an AI identity, add an AI
  `Co-authored-by` or other contributor trailer, or add "generated by AI"
  signatures to commit messages. AI contributions are acknowledged in file
  headers instead (see "Acknowledging AI and other sources").
- Do not squash or amend commits after they are pushed.

## Pull requests

- One issue per PR. Never mix features, refactors, and formatting-only changes.
- Size: aim for a PR a teammate can review in about 30 minutes, roughly 500
  changed lines excluding generated files. This is a guideline, not a hard cap.
  If a PR exceeds about 1000 lines, it should likely be split into smaller PRs.
- The PR title follows the commit subject convention. The description states
  the issue (`Closes #N`), what changed and why, the design choices a reviewer
  should know about, the tests added, the commands run with results, any
  Security Rules change, and any setup step or known limitation. Note
  explicitly any check you could not run.
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
6. If the change adds or changes data access for events, groups, chats, or
   user data, the Security Rules cover it and the process in "Firestore
   Security Rules" is followed, including local rules test results in the PR.
7. Contributor comments are added or updated at the top of every file that AI
   or an external source contributed to.
8. The README is updated if setup, configuration, or run steps changed.
9. The PR description is complete as described above.

**Merging is done by the human author, only when:** the definition of done
still holds on the latest commit; CI is green on the latest commit; the branch
is up to date with `main`; at least one other teammate has approved after
reading the diff (with explicit sign-off on any Security Rules change) and all
threads are resolved. Merge with a merge commit, not squash, so individual
commits stay in the history. Board updates, branch deletion, and closing the
issue are also the human author's job.

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
- Push to `main`, force-push a shared branch, or rewrite pushed history.
- Use `--no-verify`, skip or delete tests, weaken assertions, or disable CI
  steps or lint rules to get green.
- Loosen a Security Rule, or change rules without following the process in
  "Firestore Security Rules".
- Modify `.github/workflows/`, authentication logic, or repository settings
  without team approval and a note in the PR.
- Commit secrets, service-account files, or the Sonar token.
- Use paid Firebase services (Cloud Storage, Cloud Functions) without a team
  decision.
- Implement map tile prefetching or offline tile storage.
- Claim a check passed without running it.
- Guess at an unspecified product or interface decision.

## When blocked or unsure

Stop. Do not choose an interpretation silently. State the question, the options
you see, and your recommendation in the issue, the PR, or a message to the
human, and wait for an answer. This covers unclear acceptance criteria, missing
Gradle tasks, config, or rules test commands, conflicting instructions, and any
change that would touch the "Never do" list.

## Agent workflow

1. Restate the issue's goal and acceptance criteria. Inspect the smallest
   relevant part of the codebase and the existing tests.
2. Implement one reviewable change consistent with the current architecture and
   the conventions above, with tests in the same change.
3. Run `ktfmtFormat`, then the commands in "Build and test commands" (plus the
   rules tests if Security Rules changed). Inspect failures and fix their
   causes.
4. Check the result against the definition of done, including a review of the
   full diff for accidental files, leaked credentials, missing contributor
   comments, error handling, and real test assertions.
5. Open or update the PR with the required description. Leave code review,
   approval, and the merge decision to the team.