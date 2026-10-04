<!-- Contributors: Claude (drafted the initial content from the repository, the wiki and the Scrum Board). -->

# Project context

What a coding agent (or a new teammate) needs to know before starting a session and can't easily get from the code: the current state, the decisions that override older text, and the traps. Process rules live in `AGENTS.md`, the design in the wiki's [Architecture Diagram](https://github.com/swent-polysocial/PolySocial/wiki/Architecture-Diagram) (source: `docs/Architecture-Diagram.md`). **Keep this file short.** Only add what changes how someone should build things (see "Session context" in `AGENTS.md`).

## Current state

- **Code is still the course template.** `MainActivity` (greeting), `SecondActivity`, `SimpleData.kt` (`Point`, Euclidean, not the haversine distance we need), `ui/theme`, `resources/C.kt` (test tags). No navigation, ViewModel, repository or Maps code exists yet. Sprint 1 issues #29–#52 build the first real code.
- **Package** `com.polysocial`, minSdk 28, compile/target SDK 37, JVM 17. Dependencies use the version catalog `gradle/libs.versions.toml`. Today it has Compose, Firebase Auth + Firestore, Hilt (with KSP), Kaspresso and Robolectric. **Maps Compose, Compose Navigation and location services are not added yet.**
- **Security Rules** `firebase/firestore/firestore.rules` currently allow **any signed-in user to do anything**. #33, #35, #47 and #52 replace this. Emulator config is in `firebase.json` (Auth 9099, Firestore 8080).
- **Setup:** the README's "Setup" section lists the two private, git-ignored files every clone needs: `local.properties` (SDK path) and `app/google-services.json` (Firebase config). CI creates both from GitHub secrets. `.gitignore` also covers keystores, `secrets.properties`, `.env` files and service-account JSON. Check that `git status` never lists them before committing.
- **Missing setup (stop and ask, per `AGENTS.md`):** no rules-test command is documented, and there is no Maps key or Secrets Gradle plugin.
- **CI** (`.github/workflows/ci.yml`): the "Build and test" job runs ktfmt, the debug build, lint, unit tests and instrumented tests on an API 34 `google_apis` emulator with **no camera**, then JaCoCo and Sonar. "Release build" runs in parallel. The required check `CI` passes only if both succeed, so a new job must be added to its `needs`. PRs that only change Markdown or `docs/` skip both jobs. CI does **not** start the Firebase emulators.
- **PRs** must use `.github/pull_request_template.md`. Merges are **rebase merges**, so every commit lands on `main` as-is.
- **Scrum Board:** Product Backlog user stories are **draft items**, with no issue number. A Sprint task becomes a real **issue** when it is planned (course rule), so issue-numbered branches and `Closes #N` exist only for Sprint tasks. The fields are Task Type (Frontend / Backend / Figma, multi-select), Epic (dropdown), Priority (P0 high, P1 medium, P2 low) and Estimated / Actual Time (h) as numbers. Labels are GitHub's defaults only. The old user-story issues (#3–#28, #36) are closed, and their stories live on as drafts.

## Decided design (follow this, even where an issue still says otherwise)

- **Layers:** Compose screen → ViewModel (one `StateFlow` UI state) → repository interface → Firebase/device implementation, with a `Fake…` implementation for tests. Pure logic (validation, haversine distance, filtering, matching, QR parsing) goes in plain Kotlin with no Android/Firebase imports.
- **Names fixed by Sprint 1 issues:**
  - `AuthRepository` (`signUp`, `logIn`, `logOut`, `currentUser`, `sendVerificationEmail`, `reloadAndCheckVerified`, returning a sealed Result) and `AuthViewModel` (#30–#32).
  - `UserProfileRepository` (`createProfile`, `getProfile`, `updateProfile`) on `users/{uid}` (#34).
  - `EventRepository` (`createEvent`, `getUpcomingPublicEvents(windowDays)`) on `events/{id}` (#45, #49).
- **Profile created at the first verified entry**, not at sign-up. The rules require `email_verified`, so a write right after sign-up is denied. Create it exactly once, on Verify Email "Continue" or when app-start routing finds a verified user without a profile.
- **Anyone can create an event** and becomes its organizer. `Event.organizerIds` (always contains `createdBy`) controls who can manage it. Only a verified association (`isAssociation && isAssociationVerified`) may set `isAssociationEvent`, which shows a verified badge. There is no access-denied state on Create Event.
- **Private events** carry `allowedUids` (organizers, members, approved requesters). Rules check `request.auth.uid in resource.data.allowedUids`.
- **Profiles are split.** `users/{uid}` is owner-only. `publicProfiles/{uid}` holds visible fields, with a public or private profile (Instagram-style). Section, year and interests are always visible (matching needs them).
- **Matching** is a deterministic heuristic (tag overlap / first-fit) in a pure module, run **on the device**. Group joins go through a Firestore transaction (capacity). A Cloud Function only if fairness or cheating becomes a problem.
- **Create Event** opens from a "+" button on the Events and Map tabs. After creating, the app opens the new event's detail screen.
- **Chats:** a one-to-one chat is a two-member group (one model, one set of rules).
- **Find my group:** positions in `groups/{id}/locations/{uid}`, written only during the event, readable by members only, deleted afterwards. No location history.
- **Approved libraries (not added yet):** WorkManager for reminders (scheduled at registration, notification permission asked then). CameraX + ML Kit barcode scanning for QR codes, with typed payloads `polysocial://checkin/{eventId}/{token}` and `polysocial://friend/{uid}` checked by a pure parser.
- **DI: Hilt** (`@HiltViewModel`, constructor injection). Tests replace repositories with the `Fake…` versions through Hilt test modules. Set up in #70: `PolySocialApp` is the `@HiltAndroidApp` class, `MainActivity` is an `@AndroidEntryPoint`, and Compose gets ViewModels with `hiltViewModel()` (no navigation dependency needed). Each feature adds its own Hilt module for its repositories. Chosen after review over manual DI because it scales as repositories grow.
- **Back button:** at the root of the Map, Chats or Profile tab, back goes to the Events (home) tab, and at the Events root it exits (Android's standard). This overrides #42's current text.
- **Associations** are verified manually (a flag set in the Firebase console, to be documented in the README). Section-exclusive events are deferred.
- **Offline** relies only on Firestore's offline cache. No Room, no map tile prefetching.

The issues for the last four points (#12, #31, #32, #34, #35, #44–#47, #52) still describe the old design until they are edited.

## Traps

- **Security Rules are not filters.** A query that could return a document the user can't read fails entirely. Public events need `where isPrivate == false`. Private events need `array-contains` on `allowedUids`.
- `isPrivate == false` combined with a `startTime` range needs a **composite index**. Version it in `firestore.indexes.json`, which doesn't exist yet.
- After the email is verified, **force an ID-token refresh** (`getIdToken(true)`), or the rules still see `email_verified == false`.
- Distance on the map is **straight-line** (haversine), labelled as such. Route-based distances are out of scope.
- **Map and geocoding providers may change** (Mapbox is under consideration), so keep them behind the repository and service interfaces. If Nominatim is used, its policy allows explicit search only, at most 1 request per second, with a custom User-Agent and OSM attribution.

## Open questions (don't guess, ask)

- **Map provider:** Google Maps (current default) or Mapbox (recommended by the coaches)?
- **Sign-in providers:** add Google or Microsoft sign-in next to email/password? Every account still needs a verified `@epfl.ch` email (checked by the rules). Only email/password needs our Verify Email screen, because Google and Microsoft deliver already-verified emails.

Add new ones here and in the Architecture Diagram's "Design decisions" section.

## Decision log

Newest first, one line each, with a link. Remove a line once its content lives in "Current state" or "Decided design" and it's older than a sprint, since git history keeps it.

- 2026-10-04 · CI split into parallel "Build and test" and "Release build" jobs behind the single required `CI` check. Docs-only PRs skip the build. (#67)
- 2026-10-04 · Secrets and machine files git-ignored, README setup section added, and a pre-commit secrets check required in `AGENTS.md`. (#64)
- 2026-10-04 · After review: DI is Hilt, back goes to the home tab then exits, the map provider may switch to Mapbox. (#61, #62)
- 2026-10-04 · Board reorganised: Product Backlog stories became drafts, custom Priority P0–P2, Task Type / Epic dropdowns, time fields in hours, default labels only. (Scrum Board, no PR)
- 2026-10-03 · Decisions 5–13: Create Event "+" button, matching on the device, Find my group storage, WorkManager, CameraX + ML Kit, manual association verification, one-to-one chat as a group, manual DI. (#61)
- 2026-10-03 · Profiles split into `users` (private) and `publicProfiles` (public or private visibility). Events get `organizerIds`, `allowedUids` and `isAssociationEvent`. Anyone can create events. The profile is created at the first verified entry. (#61)
- 2026-10-03 · This file added. Agents read it first and update it with important decisions only. (this PR)
- 2026-10-03 · PR size cap of about 1000 changed lines. The backend-SDK import ban in ViewModels and Composables is kept. (#57)
- 2026-10-03 · Rebase merge, not squash. Docs-only changes don't need an issue (`chore/<slug>` branch). (#58)
- 2026-10-03 · Architecture Diagram added to the wiki, with its source in `docs/`. (#60)
