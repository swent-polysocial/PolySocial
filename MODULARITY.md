<!-- Contributors: Claude (Opus 5.5, Anthropic) drafted this guide from the coaches' feedback on the codebase's growth and Lucas's report of an agent reusing stale fields; Konstantinos Perdikeas (directed, reviewed and owns it). -->

# Modularity and code reuse

PolySocial's code and tests are growing fast. This guide makes every change reuse what the
codebase already has **when, and only when, it truly fits**, and adapt it correctly, so the
codebase grows slower without getting less correct.

**Who reads it:** every coding agent and every teammate, before writing code for a task and again
before opening or updating a PR. `AGENTS.md` makes it mandatory. If this file and `AGENTS.md` (or
the wiki's Team Agreement) ever disagree, they win: flag the mismatch instead of picking one.

## 0. The two failures this guide prevents

1. **Duplication:** writing a second copy of something that already exists (the coaches found
   `FieldLabel` three times and `OfflineBanner` twice at M1). Copies drift apart, and a bug fixed in
   one stays in the others.
2. **Wrong reuse:** reusing code that *looks* right but means something else, is outdated, or was
   not adapted to the new situation. An agent once reused fields from weeks-old code for an
   unrelated feature. Wrong reuse is **worse** than duplication: it compiles, often passes the
   tests, and silently corrupts data, leaks private fields or breaks the other feature that shares
   the code.

**Correctness always wins.** Reuse is never a reason to change behavior, drop a check or skip a
test. When in doubt whether something fits, it doesn't fit yet: check more, or write new code and
say why (section 7).

## 1. Search before writing

Before writing any new function, class, composable, string, Firestore mapping, rule function,
fake or test helper:

1. **Name what you need** in plain words: its meaning, its inputs and outputs, and its failure
   cases. For example, "the dark-or-outlined pill a choice chip draws", not "a Box with a border".
2. **Search the current code on your up-to-date branch** (rebased on `main`), with several queries,
   not one:
   - the concept in words likely to appear in names and KDoc (`chip`, `banner`, `initials`,
     `retry`, `offline`);
   - the Firestore collection and field names, and the Security Rules function names;
   - the user-facing text (search `res/values/strings*.xml`) and the test tags;
   - the types involved (who already takes or returns a `UserProfile`, `Event`, `…Result`).
3. **Look in the shared homes first:**
   - `ui/common` for UI parts (`FormParts.kt`, `ConfirmationSheet.kt`, `Chips.kt`, `LoadingState`,
     `ErrorState`, …);
   - `model/<feature>` for pure logic and repository helpers (for example `reportFailures` in
     `model/user/FirestoreFailures.kt`);
   - `app/src/sharedTest` for fakes shared by unit and device tests;
   - shared helpers under `app/src/test` and `app/src/androidTest` (for example `FirebaseEmulator`);
   - the functions at the top of `firebase/firestore/firestore.rules`;
   - `CONTEXT.md` ("Decided design", "Traps"), which names the shared parts and why they exist.
4. **Open and read every candidate in full:** its code, its KDoc, its callers (search for its name)
   and its tests. A name in a search result is not enough to reuse it.
5. **Write down the outcome** for the PR (section 8): reused, adapted, or searched and nothing
   suitable.

**Searching finds; it never invents.** If nothing suitable exists, write new code. Never call,
import or describe a function, field, class, resource or file you have not opened in the current
code. A name you remember, or one that "should" exist, does not count.

## 2. Only the current code counts

Reuse only what exists **in the current code**, never:

- code from memory, an earlier session, another branch, a closed or unmerged PR, an old commit, or
  an issue's text;
- field or collection names from documentation alone, Figma, chat or an issue: confirm each in the
  code that reads and writes it (for example `toFirestoreMap()` or a `…FromFirestore` reader) and
  in `firestore.rules`;
- anything marked superseded, deprecated or "until #N" in `CONTEXT.md` or its KDoc.

`CONTEXT.md` can be stale. When it disagrees with the code, the code shows what runs; flag the
mismatch (as `AGENTS.md` says) instead of building on either one blindly.

## 3. Does the candidate really fit? Check every point

Reuse a candidate unchanged only if **all** of these hold. A "no" on any of them means adapt it
(section 4) or don't reuse it (section 7).

| Check | Question | Example of a mismatch |
|---|---|---|
| Meaning | Does it mean the same thing, not just have the same shape or a similar name? | `createdBy` (the creator) vs `organizerIds` (everyone who manages the event); `users/{uid}` (private) vs `publicProfiles/{uid}` (what others see) |
| Data | Same source, same fields, same units, time zone and format? | an `Instant` vs a local date; a code (`BA3`) vs its label |
| Privacy and access | Does it expose, read or write exactly what the new use may, no more? | reusing a mapper that includes the email for a document others can read |
| Failure behavior | Same typed results and offline behavior, and does every caller handle them? | a read served from the cache offline vs a write that must refuse offline |
| Lifecycle and scope | Same lifetime, account and thread? | an app-wide singleton cache reused where data belongs to one account (`CONTEXT.md` trap on `CachedAssociationNames`) |
| Security Rules | Does a reused rule function grant exactly the access the new rule needs? | `isEpflUser()` (any verified `@epfl.ch` account) vs `isStudent()` (also not an association) |
| Currency | Is it still used and correct on `main`, with passing tests? | a helper kept only for a removed screen |
| Design | For UI: does the Figma frame for the new screen show the same element, size and text? | a button that looks alike but has another height or style in Figma |

## 4. Adapting code correctly

When a candidate almost fits:

1. **Change it through its contract.** Prefer a parameter with a default that keeps today's
   behavior, or extracting the common core into a smaller function that both uses call. Do not add
   flags that make one function do two different things; if it needs several flags, the two uses
   are not the same thing (section 7).
2. **Find every caller** (search the whole repository, including tests, `androidTest`,
   `sharedTest`, resources and navigation routes) and make sure each still behaves exactly as
   before, unless the change is the point of the task and the PR says so.
3. **Adapt every detail to the new use:** names, KDoc, field names, string resources, test tags,
   content descriptions and limits. Leftovers from the old context (a test
   tag, a string or a field that belongs to another feature) are bugs.
4. **Test both sides:** the existing callers' tests must still pass unchanged, and the new use gets
   its own tests, including its failure cases. If an existing test has to change, the PR explains
   why.
5. **Update the KDoc** of the shared code so it describes all its uses, and `CONTEXT.md` and the
   Architecture Diagram if a shared part is added or its meaning changes.

## 5. Extracting shared code

- **Extract when there is a real second use** with the same meaning, not for a use that might come
  later (`AGENTS.md`: no speculative abstractions).
- **Put it in its shared home** (section 1, step 3), with KDoc and its own tests if it has logic.
- **Scope:** a small extraction that the task needs goes in the same PR, in its own `refactor:`
  commit, with all tests passing before and after it. Removing larger existing duplication, or
  touching code outside the task, needs its own issue and PR (`AGENTS.md`: one issue per PR, never
  mix features and refactors).
- **Moving or sharing code never changes its behavior** in the same commit.

## 6. Minimal code is not missing code

Write the least code that is **fully correct**, not the least code possible. Never remove or skip
any of these to make a change smaller:

- error handling, typed results, the offline and cancellation paths, and the final
  `catch (Exception)` that `AGENTS.md` requires;
- input validation, limits and Security Rules checks (never loosen a rule);
- loading, empty, error and offline states, and their way out (Retry);
- tests, including failure-path tests, and test tags;
- required KDoc and contributor comments;
- privacy safeguards (no personal data in logs, no extra fields in shared documents).

**Do not delete existing code because it looks unused** until you have proved it: search for its
name everywhere (Kotlin, XML resources, the manifest, Hilt modules, navigation routes, tests,
Firestore rules and indexes, `firebase/functions`). Deleting code is outside a task's scope unless
the issue asks for it.

## 7. When not to reuse

Write new code instead, and say why in the PR, when:

- the candidate fails a check in section 3 and adapting it would change its meaning for existing
  callers;
- the two uses look alike today but belong to different features that will change for different
  reasons (sharing them would couple the features);
- reusing would widen access, expose a field, or weaken a check;
- you can't confirm the candidate is correct and current (section 2).

Two small, clear, separate pieces of code are better than one shared piece with two meanings.

## 8. In the PR

Under "Design choices", add a short **Reuse** note:

- **Reused:** what, from where, unchanged.
- **Adapted:** what changed in shared code, why, which callers were checked and which tests cover
  them.
- **Searched, not reused:** what you looked for and why it didn't fit (or that nothing existed).

Reviewers check this note against the diff: look for new code that duplicates existing code, and
for reused code whose names, fields, strings or tags still belong to its old context.

## 9. Before finishing: check your own diff

- Search the diff's new functions and composables against the codebase once more: did you write
  something that already exists?
- For every reused or adapted piece, go through section 3 again for the new use.
- Make sure no leftover from another context remains: field names, string resources, test tags,
  KDoc and comments.
- Run every test touched by shared code, not only the new ones (`./gradlew check`, plus
  `connectedCheck` and the rules tests when they are affected, as `AGENTS.md` lists).

## 10. When unsure

Stop and ask, as `AGENTS.md` says under "When blocked or unsure": say what you would reuse, what
you are unsure about, the options, and your recommendation. A wrong guess in shared code breaks
features other teammates own.
