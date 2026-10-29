<!-- Contributors: OpenAI Codex (Google sign-in setup and review clarifications for #106). -->
<!-- Contributors: OpenAI Codex (Google entry behavior for #107). -->
<!-- Contributors: Claude (drafted the README structure, Setup, Running and testing, Tech stack and Project links sections; Firestore index deployment, #49; Firestore emulator tests, #117); OpenAI Codex (documented startup routing and rebase reconciliation); OpenAI Codex (Mapbox integration and setup, #50; one-off Map location privacy note, #51). -->
<!-- Contributors: OpenAI Codex (verification startup integration and Back sign-out, #31). -->
<!-- Contributors: Claude (Sprint 2 status in the Features table and the startup routing note). -->
<!-- Contributors: OpenAI Codex (bundled Profile privacy notice and unavailable actions, #103). -->

<!-- Contributors: OpenAI Codex (CI APK download, installation and Mapbox setup instructions, #137). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) drafted the Cloud Functions section, #206; Mohamed Khellaf (reviewed and revised). -->

# PolySocial

[![CI](https://github.com/swent-polysocial/PolySocial/actions/workflows/ci.yml/badge.svg)](https://github.com/swent-polysocial/PolySocial/actions/workflows/ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=swent-polysocial_PolySocial&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=swent-polysocial_PolySocial)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=swent-polysocial_PolySocial&metric=coverage)](https://sonarcloud.io/summary/new_code?id=swent-polysocial_PolySocial)

> Never go alone. Find a group to attend EPFL campus events with.

An Android app built for the EPFL Software Enterprise (SwEnt) course.

## About

Many EPFL students, in particular first-years and exchange students, skip campus events. It's not that they don't hear about them (Telegram, Instagram and posters already cover that). It's that they have nobody to go with and don't want to show up alone.

PolySocial turns event discovery into group discovery. A student browses upcoming events on an interactive Lausanne map, taps **"Find a group"** on one, and is matched with a small group of other students going to the same event, based on shared interests, common friends, section and year. The group gets a chat to plan the outing, checks in together at the venue, and shares its photos in the event album.

The barrier to campus life is social rather than informational: PolySocial answers *"who do I go with?"*.

## Features

| Feature | Status |
|---|---|
| Sign up, log in and password recovery with an EPFL email (verified email required), profile with section and year | Sprints 1–2 |
| App shell: Events, Map, Chats and Profile tabs, one back stack per tab | Sprint 1 |
| Events list with search and filters (category, date, distance), grouped by day | Sprint 2 |
| Event detail screen | Sprint 2 |
| Interactive map of upcoming public events, with straight-line distance when location is allowed | Sprint 2 |
| Create events: private (off the map, people ask to join) or public, with a map location picker and place search; verified associations get a badge | Sprint 2 |
| Sign in with Google (EPFL accounts only) | Implemented; Firebase console setup required |
| "My events" available offline | Planned |
| **Find a group:** matching on interests, section, year and common friends; group proposals | Planned |
| Group chat, with report and block | Planned |
| One-off check-in at the venue (GPS read once, or an entrance QR code) and opt-in location sharing with your group until the event ends | Planned |
| Shared event album, unlocked by check-in | Planned |
| Friends (by username or QR code), public or private profiles | Planned |
| Association accounts with members, verified manually | Planned |
| Event reminders (also offline) and in-app notifications | Planned |

**Privacy:** location is never tracked continuously. It is read once for a feature, or shared only with your group, only during the event, and only when you choose to. Profiles, photos, chats and locations are personal data under the Swiss nLPD, and account/data deletion can be requested privately from the project team; in-app deletion and export are not available yet.

Profile → **Privacy & data** opens the current collection/access summary and a dated full notice, both bundled for offline reading. Attendance-based matching, export and deletion actions show **Not available yet**; the matching switch stays Off. The prototype explicitly marks the controller contact and provider processing countries/safeguards as draft details. The private contact button explains that the address is pending; once the team supplies it, set `privacy_contact_email` in `app/src/main/res/values/strings.xml` to enable a user-controlled email draft with no account data attached. Replace `privacy_notice_draft_body` with the confirmed details at the same time. A missing email app is handled in the screen.

For Map distance badges, the app requests only approximate location. On first entry, the Map requests permission once if it is not already granted. The location service reads one foreground snapshot and keeps coordinates only in screen memory; it never stores or uploads them. Leaving the Map or backgrounding the app cancels any pending request and discards the snapshot without showing a location error. On return, use the floating **Refresh distance** button for another one-off fix; permission prompts and location reads do not repeat automatically. Only whether the permission prompt was requested is saved. Denying permission keeps map browsing available. **Turn on** asks again when Android allows another request, or opens this app's settings after a permanent denial. Granting permission there starts one fix on return.

**Offline:** events you've loaded, your registered events and their schedule, and reminders stay available offline. The map shows cached events on whatever map tiles are already cached.

Signed-out users see Welcome on launch. Unverified sessions reopen Verify Email; verified sessions load their profile before opening the app or profile setup. The screen sends a verification email, supports resend with a persistent cooldown, and checks verification on Continue or browser return. After verification, shared startup routing loads the profile and opens the app or the profile step; failed profile reads show Retry. Both the system Back action and the back icon sign out and reset the forms, so reopening shows Welcome. Change address also signs out and opens a fresh sign-up form. Verification uses Firebase’s browser action page, so no Hosting or App Links setup is needed.

## Tech stack

- **Kotlin** and **Jetpack Compose** (Material 3), **MVVM** with `StateFlow` UI state; dependency injection with **Hilt**
- **Firebase:** Authentication, Cloud Firestore (with offline persistence), Cloud Storage for photos, Cloud Functions (account deletion), Local Emulator Suite for tests
- **Maps:** Mapbox Maps SDK and its Compose extension; **Nominatim / OpenStreetMap** for geocoding
- **Device:** GPS (one-off location), CameraX + ML Kit for QR codes, WorkManager for reminders
- **Quality:** JUnit, Robolectric, Kaspresso, JaCoCo coverage, SonarCloud, ktfmt, GitHub Actions CI

The design of the app is on the wiki's **[Architecture Diagram](https://github.com/swent-polysocial/PolySocial/wiki/Architecture-Diagram)** page: layers, navigation, data model, Security Rules and design decisions.

## Setup

The app needs two private files that are **not in the repository**. Both are git-ignored and must never be committed, because the repository is public.

| File | What it holds | How to get it |
|---|---|---|
| `local.properties` (repository root) | The Android SDK path: `sdk.dir=/path/to/Android/Sdk`. Also `MAPBOX_ACCESS_TOKEN`, the team’s dedicated public Mapbox token. | Android Studio creates it when you open the project. Otherwise, write that one line yourself. |
| `app/google-services.json` | The Firebase configuration for `com.polysocial` | Firebase console → Project settings → *Your apps* → Android app `com.polysocial` → download `google-services.json`. Or ask a teammate. |

**Prerequisites:** Android Studio with the Android SDK (compile SDK 37), and JDK 17 or newer.

Then build with `./gradlew assembleDebug` (on Windows, `./gradlew.bat assembleDebug`). CI creates both private files from GitHub secrets.

### Mapbox setup

The prototype keeps the current account and signup public token. The team decides production billing, usage thresholds and token rotation before release; this setup does not add a payment method.

One **public** (`pk.`) token is enough for the initial PolySocial prototype, including teammates' local builds. The signup token can be used for initial development. Card-free trial accounts currently allow 100 monthly active mobile users and cannot create additional tokens until a payment method is added; see [Mapbox signup and trial limits](https://docs.mapbox.com/accounts/guides/signup/). Before production, create a dedicated PolySocial public token with only `styles:read` and `fonts:read` scopes. Separate development/release tokens can help track usage and rotate credentials independently, but are not required for the prototype.

Put `MAPBOX_ACCESS_TOKEN=your-public-token` in each checkout's git-ignored root `local.properties`. Gradle generates `mapbox_access_token`; never commit a token resource or package a secret (`sk.`) token. A public token is included in the installed app, so minimal scopes and usage monitoring still matter. Downloads from the Mapbox Maven repository need no secret token. The `ndk27` artifacts support Android’s 16 KB memory page requirement.

Mapbox mobile tokens cannot use Google’s package/SHA-1 restrictions; URL restrictions are intended for browser requests and must not be enabled for this Android token. For production, use a dedicated token per app/environment, minimal scopes, usage monitoring and rotation. Configure email usage notifications for the Maps SDK in the team’s Mapbox account settings, with monthly-active-user thresholds agreed by the team. These alerts do not stop billed usage; Mapbox has no configurable spending cap. Keep the separate Firebase/Google Cloud budget alert in place. See [Mapbox installation](https://docs.mapbox.com/android/maps/guides/install/), [token restrictions](https://docs.mapbox.com/accounts/guides/tokens/) and [usage notifications](https://docs.mapbox.com/accounts/guides/invoices/#spending-alerts).

Without a token, CI and local builds still compile and the map displays a setup state. The account/token must be provided before native map rendering, tile errors and billing can be verified on a device. The map observes `EventRepository.getUpcomingPublicEvents()` through `RepositoryMapEventSource`: public events update live, cached events remain usable offline, and Retry starts a new collection after a query error. The 14-day window is fixed when collection starts. Event-query Retry preserves a healthy map and its camera; renderer-only Retry keeps the event listener and preview. Individual resource failures after first map load leave cached areas and markers usable, with unavailable tiles potentially blank; only initial/style failures show the map Retry state. No fixture events are used in production.

**Map privacy:** Mapbox receives map resource requests for the viewed area. The renderer does not enable a location puck, GPS subscription or geofencing. Optional Mapbox telemetry and geofencing consent are set to disabled through the Compose attribution control when the map is created; SDK initialization/accounting requests still occur. Mapbox’s logo, attribution and telemetry preference dialog remain visible. No map tile prefetching or explicit offline region downloads are added; tiles already cached by the SDK remain usable. The shared app notice under Profile → Privacy & data is tracked in [#103](https://github.com/swent-polysocial/PolySocial/issues/103), with ownership and wording to be decided by the team.

Before every commit, check that `git status` never lists these files. If it does, fix `.gitignore` first and don't commit them.

**Firestore indexes:** the composite indexes the app's queries need are versioned in `firebase/firestore/firestore.indexes.json`. When that file changes on `main`, someone with access to the Firebase project deploys it with the [Firebase CLI](https://firebase.google.com/docs/cli) (`firebase login` first):

```bash
firebase deploy --only firestore:indexes --project <project-id>
```

`<project-id>` is the `project_id` in your `app/google-services.json`.

Keep `--only firestore:indexes`: a plain `firebase deploy` would also deploy the Security Rules. A new index takes a few minutes to build (Firebase console → Firestore → Indexes). Until it's ready, the query that needs it fails. The local emulator doesn't need indexes.

## Downloading the CI APK

Sign in to GitHub (artifacts cannot be downloaded anonymously). After a successful push to `main`, open [CI - Test Runner](https://github.com/swent-polysocial/PolySocial/actions/workflows/ci.yml), choose the successful run for the desired commit on `main`, and download **polysocial-apk** from its **Artifacts** section. Extract the ZIP to obtain `app-debug.apk`. If an older PolySocial is installed, uninstall it first: each CI run generates a different debug signing key, so Android cannot install the new APK as an update. Uninstalling removes the app's local data. Install on an Android phone running Android 9 (API 28) or newer, allowing installation from that source when Android asks.

The APK is built from the commit whose checks, unit tests, device-test shards, Sonar analysis and release build all succeeded; the device-test shards build their own APKs. Artifacts expire after **7 days**, so use a recent successful `main` run for the Sprint review or M1. PR runs do not publish an APK. The one-day `ci-main-debug-apk` artifact is an internal handoff between jobs; download **polysocial-apk**.

For the CI APK to show the map, a repository maintainer must configure the GitHub Actions `LOCAL_PROPERTIES` secret with base64-encoded properties containing `MAPBOX_ACCESS_TOKEN=your-public-token` (see [Mapbox setup](#mapbox-setup)). A developer's local file is not available to GitHub's runner. An absent secret or token still allows a successful build, but the installed app shows the map setup state. Only use a public `pk.` token; never include a secret `sk.` token or a developer's SDK path in this CI configuration.

### Google sign-in setup

In Firebase console → Authentication → Sign-in method, enable **Google** and select a support email. Keep **one account per email address** enabled.

In Project settings → Your apps → `com.polysocial`, register the **SHA-1 fingerprints** for every teammate's debug keystore, CI, and release/Play signing keystores (`./gradlew signingReport` for local fingerprints). Download a fresh, git-ignored `app/google-services.json` and ask the repository owner to update the CI secret. The config must include the **Web OAuth client ID**, which generates `default_web_client_id`; never hardcode it or commit keystores. If an older config has no Web OAuth client (`client_type: 3`), compilation fails with **unresolved reference `default_web_client_id`** before the runtime configuration check can run. Re-download the config and update the CI secret.

**CI APK limitation:** runners currently generate a fresh debug signing key for every run, so Google sign-in cannot be configured reliably for `polysocial-apk`. The stable CI key and Firebase SHA-1 registration are tracked in [#137](https://github.com/swent-polysocial/PolySocial/issues/137). Resolve that follow-up before relying on Google sign-in in the next deliverable; each teammate must also register their local SHA-1 and download the updated config.

## Running and testing

Run from the repository root:

```bash
./gradlew assembleDebug        # build the debug APK
./gradlew installDebug         # install it on a running emulator or device
./gradlew ktfmtFormat          # format the code (run before every commit)
./gradlew ktfmtCheck lint      # formatting and lint checks, as in CI
./gradlew check                # unit tests (JVM and Robolectric)
./gradlew connectedCheck       # instrumented tests (needs an emulator or device)
./gradlew jacocoTestReport     # coverage report, as uploaded to SonarCloud
```

Firestore Security Rules (`firebase/firestore/firestore.rules`) are tested against the Firebase Local Emulator Suite, with Firebase's `@firebase/rules-unit-testing` library. This needs [Node.js](https://nodejs.org) 20 or newer, the [Firebase CLI](https://firebase.google.com/docs/cli) and Java 21 or newer (for the emulator). Install the test packages once, then run the tests from the repository root:

```bash
npm ci --prefix firebase
firebase emulators:exec --project demo-polysocial --only firestore "node firebase/firestore/firestore.rules.test.js"
```

The `demo-` project ID keeps everything on the local emulator, never a real Firebase project. CI runs these tests too, but still paste their local output in any PR that changes the rules.

Repository tests that need the real Firestore and Auth (queries, listeners, transactions, and the Security Rules on top) run against the same emulators. They are instrumented tests marked `@EmulatorTest`, in `app/src/androidTest/java/com/polysocial/emulator`. Start an Android emulator, then run from the repository root:

```bash
firebase emulators:exec --project demo-polysocial --only firestore,auth "./gradlew connectedDebugAndroidTest -Pemulator"
```

On Windows, `emulators:exec` runs the script with `cmd.exe`, which doesn't understand `./gradlew`. Use `gradlew.bat` without `./`:

```powershell
firebase emulators:exec --project demo-polysocial --only firestore,auth "gradlew.bat connectedDebugAndroidTest -Pemulator"
```

`-Pemulator` runs **only** the emulator tests, in place of the regular instrumented tests, so run `./gradlew connectedCheck` separately for those. Without the flag, `connectedCheck` skips the emulator tests. `-Pemulator=all` runs both, which is what CI does inside `firebase emulators:exec`. The tests reach the host machine at `10.0.2.2`, which only works from an Android emulator, not from a physical device.

## Cloud Functions

Server code lives in `firebase/functions` (plain JavaScript, Node.js 22, region `europe-west6`). Today it has one callable function, **`deleteAccount`**: it deletes the signed-in user's private profile `users/{uid}`, then their Firebase Auth user, and returns `{ deleted: true }` only once both are gone. It refuses a sign-in older than 5 minutes (`failed-precondition`, details `recent-login-required`) and association accounts (`failed-precondition`, details `association`: they ask the PolySocial team through the privacy contact). Each step can be retried safely. Events the user created are kept. The Admin SDK bypasses the Security Rules, so the function itself checks who may delete what.

Install the packages once (Node.js **22.12 or newer**: `firebase-admin` depends on `jwks-rsa`, which needs `^20.19 || ^22.12 || >=23`), then run the unit tests, which use fakes and need no emulator:

```bash
npm ci --prefix firebase/functions
node firebase/functions/deleteAccountHandler.test.js
```

The emulator test calls the function over HTTP, like the app, with the Auth, Firestore and Functions emulators:

```bash
firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node firebase/functions/deleteAccount.emulator.test.js"
```

Use the npm Firebase CLI (`npm install -g firebase-tools`, as CI does) with Node.js 22.12 or newer. The standalone CLI (`curl -sL firebase.tools | bash`) ignores the installed Node.js and runs the function with its own bundled Node 20, which fails to load the function (`ERR_REQUIRE_ESM`) before any test runs; the same applies to the deploy below, which loads the code locally first. The emulator warns if your Node.js isn't version 22; the tests still run on a newer one.

**Deploying is manual.** After a change to `firebase/functions` reaches `main`, a teammate with access to the Firebase project deploys it (`firebase login` first). Cloud Functions need the Blaze plan, which the project uses, with its budget alert:

```bash
npm ci --prefix firebase/functions
firebase deploy --only functions --project <project-id>
```

Keep `--only functions`: a plain `firebase deploy` would also deploy the Security Rules and indexes. The app calls the function in `europe-west6`; if the region changes, change it in both `firebase/functions/deleteAccountHandler.js` (`REGION`) and the app.

**Not in CI yet:** CI runs neither the function's unit tests nor the Functions emulator, and doesn't deploy. Adding them needs a workflow change, which needs team approval, so run both commands above locally and paste their output in any PR that changes `firebase/functions`.

## Project links

- **Figma mockups:** [First proposal revamped](https://www.figma.com/design/7Gt7tmy1ikpNyNBh1P6QdY/App-Mockup?node-id=123-467) (the team-agreed design)
- **Scrum Board:** [GitHub Project](https://github.com/orgs/swent-polysocial/projects/1). The Product Backlog is draft items, and Sprint tasks are issues.
- **Wiki:** [Home](https://github.com/swent-polysocial/PolySocial/wiki), with the [Team Agreement](https://github.com/swent-polysocial/PolySocial/wiki/Team-Agreement) and [Architecture Diagram](https://github.com/swent-polysocial/PolySocial/wiki/Architecture-Diagram)
- **Course:** [SwEnt project guidelines](https://github.com/swent-epfl/public/tree/main/project)

## Contributing

How we work (branches, commits, reviews, Definition of Done) is in the wiki's [Team Agreement](https://github.com/swent-polysocial/PolySocial/wiki/Team-Agreement). [`AGENTS.md`](AGENTS.md) applies the same rules to AI coding agents, and [`CONTEXT.md`](CONTEXT.md) records the current design decisions. In short:

- One issue, one branch, one PR: `feature/<issue>-<slug>`, `fix/…`, `chore/…`.
- Conventional Commit messages, for example `feat: add event map`.
- At least one teammate approves before a PR is rebase-merged.
- AI contributions are acknowledged in a comment at the top of each file.

## Team

| Member | GitHub |
|---|---|
| Konstantinos | [@perdikeas](https://github.com/perdikeas) |
| Lucas | [@LucasPintoRicardo](https://github.com/LucasPintoRicardo) |
| Franciszek | [@FranciszekNajda](https://github.com/FranciszekNajda) |
| Mohamed | [@mohamedkhellaf](https://github.com/mohamedkhellaf) |
| Ayoub | [@Ayoub-fct](https://github.com/Ayoub-fct) |
| Mehdi | [@mehdifddl](https://github.com/mehdifddl) |

Scrum Master and Product Owner rotate every Sprint: see [Roles & Rotation](https://github.com/swent-polysocial/PolySocial/wiki/Roles-and-Rotation).
