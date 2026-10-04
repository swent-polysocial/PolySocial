Closes #

## What changed and why
<!-- The behavior change and the reason for it. A reviewer should understand the PR without opening the issue. -->

## Design choices
<!-- What a reviewer should know: alternatives considered, trade-offs, follow-up work (with issue numbers). Write "None" if there are none. -->

## How it was tested
<!-- Tests added or changed, and the commands you ran locally (e.g. ./gradlew ktfmtCheck, ./gradlew check, ./gradlew connectedCheck) with their result. List anything you could not run. -->

## Screenshots
<!-- For UI changes: before/after screenshots or a short recording. Delete this section otherwise. -->

## Checklist
<!-- From the wiki Team Agreement (review checklist and Definition of Done). Tick what applies; explain anything left unticked. -->
- [ ] Linked to its issue (`Closes #N` above) and has an assignee
- [ ] CI is green: build, lint and all tests pass
- [ ] New logic comes with tests that assert behavior (no assertion-free tests)
- [ ] Coverage on new code is at least 95% lines and 65% branches, and overall coverage does not decrease
- [ ] At most ~1000 changed lines of production code (tests, fakes, fixtures, resources and generated files excluded); tests for the change are in this PR
- [ ] Failures (network, Firestore, permissions, invalid input) are handled and surfaced in the UI state
- [ ] MVVM is respected: no business logic or data access in composables
- [ ] No live network calls in tests
- [ ] Commits written with someone else include a `Co-authored-by:` trailer
- [ ] Acceptance criteria are met and demoable on the emulator or a device
- [ ] README, wiki or KDoc updated if needed
- [ ] If Firestore Security Rules changed: rules tests cover the change and the reviewer is asked to check the rules
