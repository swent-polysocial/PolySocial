---
name: code-review
description: Review a PolySocial pull request or local diff against the team's review checklist (AGENTS.md and the wiki Team Agreement). Use when asked to review a PR, check a branch before opening a PR, or decide whether a change is ready for review.
---

<!-- Contributors: Claude Code (drafted from AGENTS.md, the wiki Team Agreement and common review practice); Mohamed Khellaf (requested and reviewed). -->

# PolySocial code review

This skill reviews one PolySocial change and produces review comments a
teammate can act on. The sources of truth are `AGENTS.md` at the repository
root and the wiki page
[Team Agreement](https://github.com/swent-polysocial/PolySocial/wiki/Team-Agreement).
Read both before reviewing. If they disagree on a point, follow the stricter
rule and mention the disagreement in the review summary.

An AI review never replaces a teammate's approval. Never approve, request
changes, merge, or dismiss a review. Post comments only when the person who
asked for the review says so. Say that the review was AI-assisted.

## 1. Gather context

1. Identify the target. It can be a PR number, a branch, or the local diff
   against `main`.
   - PR: `gh pr view <N>`, `gh pr diff <N>`, `gh pr checks <N>`
   - Branch: `git fetch origin && git diff origin/main...<branch>`
2. Read the linked issue (`Closes #N`) and its acceptance criteria:
   `gh issue view <N>`.
3. Read the **entire** diff, including tests and resources. Open the full
   files around each change when the diff alone does not show how the code
   is used.
4. Read nearby code the change depends on (the ViewModel, repository
   interface, or screen next to it) so you review integration, not just
   lines.

## 2. Run the checks (when you can)

Run these from the repository root. They mirror `.github/workflows/ci.yml`;
if the workflow has changed, follow the workflow and say so.

```bash
./gradlew ktfmtCheck
./gradlew assemble lint
./gradlew check
./gradlew connectedCheck   # needs a running emulator or device
```

If the PR changes Firestore Security Rules, the rules tests must be run
locally against the Firebase emulator with the command in the README. CI
does not start the Firebase emulator. Report every command and its result.
If a command could not run, say which one and why. Never claim a check
passed if you did not see it pass.

## 3. Checklist

Go through every section. A finding needs a concrete reason: the file and
line, what goes wrong, and when.

### Scope and size
- The change matches the issue's acceptance criteria and nothing outside
  them. No unrelated refactors or formatting-only edits mixed in.
- About 500 changed lines is a comfortable review; above about 1000
  (excluding generated files, resources and test fixtures) it should be
  split.
- Branch is named `feature|fix|refactor|test|chore/<issue>-<slug>`.

### Correctness
- Logic does what the issue asks, including edge cases: empty lists, null or
  missing Firestore fields, duplicate taps, and rotation or process death.
- Coroutines: no blocking work on the main thread, correct scope
  (`viewModelScope`), cancellation respected, and no leaked collectors.
- State updates are atomic where concurrent writes are possible (for example
  joining a group with limited spots needs a transaction).

### Architecture (MVVM)
- Composables only render state and forward events. They hold no business
  logic and no data access.
- ViewModels expose `StateFlow` (or equivalent) UI state and never import
  Firebase, Maps, or other backend SDKs directly.
- Firebase, Maps, persistence, GPS, camera and QR decoding sit behind
  interfaces and are injected, so tests can use fakes.
- Pure logic (matching, QR payload parsing, event filtering) lives in plain
  Kotlin functions.
- No speculative abstractions for hypothetical needs.

### Error, loading and offline states
- Network, Firestore, permission and invalid-input failures are surfaced in
  UI state, not swallowed (`catch {}` with nothing inside is a finding).
- Loading, empty, success and error states are represented where relevant.
- Denied location or camera permission is handled gracefully.
- Offline behavior (Firestore offline persistence and cached events) is not
  removed or weakened. There is no map tile prefetching or offline tile
  storage.

### Tests
- New logic and every bug fix come with tests in the same PR. A bug fix has a
  test that would fail without the fix.
- Tests assert observable behavior (state, output, rendered UI) and would fail
  if the feature broke. Assertion-free tests are a finding, and so are tests
  that only exercise code for coverage.
- Tests cover failure paths: empty input, invalid QR code, no network,
  denied permission, unauthorized access.
- Tests are deterministic: no real time, randomness, or live network calls.
  Backends use fakes or the Firebase emulator, never production.
- Instrumented tests that run in CI use fakes and do not need a camera or a
  Firebase emulator.
- Existing tests were not weakened, skipped, or deleted without a stated
  reason.
- New code aims for at least 80% line and 65% branch coverage in
  JaCoCo/SonarCloud, and overall coverage does not drop. A meaningful test
  still beats a coverage gain.

### Security Rules and access control
Apply this section whenever the change adds or changes access to events,
groups, chats or user data.
- Rules cover the new access and every granting rule requires
  `request.auth != null`, `request.auth.token.email_verified == true` and an
  `@epfl.ch` email.
- A student cannot read or write another student's private data.
- Private events are readable only by the creator, members and approved
  matches, and never appear on the public map. Group chats are readable only
  by members.
- Only an association can manage its own events.
- No rule was loosened (widened match, removed condition) to make something
  work.
- The PR says "Changes Security Rules", explains what access changed, adds
  rules tests with at least one allowed and one denied case per changed
  rule, and pastes the local emulator results. Ask the reviewer for explicit
  sign-off.

### Privacy and secrets
- No logging of emails, names, locations or photos. No real user data in
  tests or fixtures.
- No secrets in the diff: private keys, service-account JSON, signing
  credentials, the Sonar token. The Maps key is read through the Secrets
  plugin from `local.properties`, not hardcoded.
- No new paid Firebase service (Cloud Storage, Cloud Functions), third-party
  service, sensor or backend without a recorded team decision.
- `.github/workflows/`, authentication logic and repository settings are
  untouched unless the PR notes team approval.

### Android and Compose
- User-facing strings are in `strings.xml`.
- Every Composable a UI test interacts with has a `Modifier.testTag`.
- State survives recomposition and configuration changes (`remember`,
  `rememberSaveable`, or ViewModel state as appropriate).
- Permissions are requested at the point of use with a rationale.

### Code quality and conventions
- Formatted with ktfmt. Lint findings are fixed, not hidden. Every
  `@Suppress`, `@Ignore`, baseline or disabled rule has a one-line
  justification.
- KDoc on repository and service interfaces, ViewModels, and non-obvious
  logic.
- No commented-out code, `println` or debug logging, and no TODO without an
  issue number.
- No new or upgraded dependency unless the issue needs it, and then through
  the version catalog.
- Names are clear, duplication is avoided, and SonarCloud issues in touched
  code are fixed.

### Commits, PR description and attribution
- Commit subjects follow Conventional Commits (`<type>(<scope>): <summary>`,
  imperative, lowercase, at most 50 characters). Commits are small and
  coherent, and a body explains the why when needed.
- The PR has an assignee and a description with `Closes #N`, what changed and
  why, design choices, tests added, commands run with results, and any check
  that could not be run.
- Commits co-written with another teammate carry a `Co-authored-by:` trailer
  for that teammate.
- Every file that AI or an external source contributed to has a contributor
  comment at the top.
- The README is updated if setup or run steps changed.

## 4. Write the review

Write in English. Talk about the code, not the author. Prefix every comment
with exactly one word:

- `Important`: a bug, missing test, rules or privacy problem, or checklist
  violation that must be fixed before merge.
- `Nitpick`: small style or naming point that does not block.
- `Question`: something unclear. Ask instead of assuming.

Each comment names the file and line, states the problem, explains the
concrete consequence (input, state and wrong result), and suggests a fix when
one is clear. Add positive comments where they are deserved. Give a bare
"LGTM" only for a tiny, obviously correct change.

Structure the output as:

1. **Summary**: what the PR does, in two or three sentences, and a verdict
   (ready for review / needs changes / blocked on a question).
2. **Checks run**: each command and its result, and what could not be run.
3. **Comments**: `Important` first, then `Question`, then `Nitpick`.
4. **Checklist gaps**: any checklist section that fails or that you could not
   verify.
5. **Acknowledgement**: one line saying the review was AI-assisted.

If the person asks you to post the review, use
`gh pr review <N> --comment --body-file <file>` for the summary, and inline
comments through `gh api` on the PR's review comments endpoint. Never use
`--approve` or `--request-changes`.
