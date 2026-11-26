<!-- Contributors: OpenAI Codex (GPT-6.2 Sol, high; #447 under Lucas Pinto Ricardo’s supervision and approval). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (JDK 21 in the prerequisites, #456 review). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (the attendee recount and the capacity deploy order, #405; the functions' deploy noted, #373). -->
<!-- Contributors: OpenAI Codex (group membership account-deletion cleanup and deploy order, #241). -->
<!-- Contributors: OpenAI Codex (manual association verification guide and PR #245 review revisions, #135). -->
<!-- Contributors: OpenAI Codex (association onboarding integration notes, #125). -->
<!-- Contributors: OpenAI Codex (Google sign-in setup and review clarifications for #106). -->
<!-- Contributors: OpenAI Codex (Google entry behavior for #107). -->
<!-- Contributors: Claude (drafted the README structure, Setup, Running and testing, Tech stack and Project links sections; Firestore index deployment, #49; Firestore emulator tests, #117; Security Rules deployment); OpenAI Codex (documented startup routing and rebase reconciliation); OpenAI Codex (Mapbox integration and setup, #50; one-off Map location privacy note, #51). -->
<!-- Contributors: OpenAI Codex (verification startup integration and Back sign-out, #31). -->
<!-- Contributors: Claude (Sprint 2 and Sprint 3 status in the Features table and the startup routing notes). -->
<!-- Contributors: OpenAI Codex (bundled Profile privacy notice and unavailable actions, #103). -->

<!-- Contributors: OpenAI Codex (CI APK download, installation and Mapbox setup instructions, #137). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) drafted the Cloud Functions section, #206; Mohamed Khellaf (reviewed and revised). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) drafted the Deploying to Firebase section, #228, and later said any team member deploys; Franciszek Najda (reviewed #228). -->
<!-- Contributors: Claude (public profiles deleted with the account, #229; the deletion marker and deploy order, after review, #229). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (deleteAccount deploy status, budget alert and image cleanup policy, 2026-11-05). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (the Seeding demo data section, #243). -->
<!-- Contributors: Claude (Fable 5.2, Anthropic) (Features table statuses, section order and the move of behavior notes to docs/App-behavior.md, #340; review fixes by Claude (Opus 5.5, Anthropic)). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (the deleteEventRegistrations trigger and registrations in deleteAccount, #275). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (the deleteEventGroups and deleteGroupMessages triggers, their tests and deploy, the event's guard on its creator, and the `testing` folder left out of the functions deploy in firebase.json, which can't hold a comment, #382). -->
<!-- Contributors: OpenAI Codex (CI signing, direct APK downloads, Firebase setup and review fixes for #383). -->
<!-- Contributors: Claude (Fable 5.2, Anthropic) (the scheduled deleteUnverifiedAccounts function, its retention period and first deploy, #226). -->
<!-- Contributors: Claude Opus 5.5 (the friendships' deploy order, #357, deleteAccount first after review); Konstantinos Perdikeas (directed, reviewed and owns it). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (the notice's deletion section that states the period, after review, #226). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (one-to-one chats and delete-for-me markers in deleteAccount, and their deploy order, #429). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (the check-in rules, TTL policy and index deploy, #467). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (the entrance code's deploy, #468). -->
<!-- Contributors: Claude (Opus 5.5, Anthropic) (check-ins deleted with the account and the event, and their deploy, #472); Konstantinos Perdikeas (directed, reviewed and owns it). -->
<!-- Contributors: Claude (Sonnet 5.5, Anthropic) (the allowedSections backfill and the deploy order of #369). -->

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
| Sign up, log in and password recovery with a verified EPFL email; sign in with Google (EPFL accounts only) | Done |
| Profile with section, year and interests | Done |
| Events list with search and filters (category, date, distance), grouped by day | Done |
| Event detail; organizers edit or cancel their own events | Done |
| Interactive map of upcoming public events, with distance when location is allowed | Done |
| Create public or private events, with a map location picker and place search | Done |
| "I'm going" and **My events**, available offline | Done |
| **Find a group:** join or start a small group for an event, ranked by shared interests, section and year | Done (group screen in progress) |
| Association accounts: sign up with any email, set up the association, verified by hand by a PolySocial admin; verified associations manage their own events | Done |
| Account deletion from Profile → Privacy & data | Done |
| Group chat | In progress |
| Event reminders | In progress |
| Friends (by QR code), public or private profiles | Planned |
| Check-in at the venue (one-off GPS read or entrance QR code) | Planned |
| Shared event album, unlocked by check-in | Planned |
| Association members and roles | Planned |
| Report and block, in-app notifications | Planned |

**Privacy:** location is never tracked continuously. It is read once for a feature, and only when you allow it. Profiles, photos, chats and locations are personal data under the Swiss nLPD; students can delete their account and its data in the app (Profile → **Privacy & data**), where the full privacy notice is also available offline; associations ask the project team. Data export isn't available yet.

**Offline:** events you've loaded, your registered events and their schedule, and reminders stay available offline. The map shows cached events on whatever map tiles are already cached.

How the privacy notice, the one-off location read and the startup routing behave in detail is in [docs/App-behavior.md](docs/App-behavior.md).

## Tech stack

- **Kotlin** and **Jetpack Compose** (Material 3), **MVVM** with `StateFlow` UI state; dependency injection with **Hilt**
- **Firebase:** Authentication, Cloud Firestore (with offline persistence), Cloud Storage for photos, Cloud Functions (account deletion, registrations deleted with their event, and a daily cleanup of unverified accounts), Local Emulator Suite for tests
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

**Prerequisites:** Android Studio with the Android SDK (compile SDK 37), and JDK 21 or newer, as CI uses: the rules emulator needs it, and the organizer initials' unit tests need a JDK whose `BreakIterator` keeps a flag emoji whole (JDK 20 or newer).

Then build with `./gradlew assembleDebug` (on Windows, `./gradlew.bat assembleDebug`). CI creates both private files from GitHub secrets.

### Mapbox setup

The prototype keeps the current account and signup public token. The team decides production billing, usage thresholds and token rotation before release; this setup does not add a payment method.

One **public** (`pk.`) token is enough for the initial PolySocial prototype, including teammates' local builds. The signup token can be used for initial development. Card-free trial accounts currently allow 100 monthly active mobile users and cannot create additional tokens until a payment method is added; see [Mapbox signup and trial limits](https://docs.mapbox.com/accounts/guides/signup/). Before production, create a dedicated PolySocial public token with only `styles:read` and `fonts:read` scopes. Separate development/release tokens can help track usage and rotate credentials independently, but are not required for the prototype.

Put `MAPBOX_ACCESS_TOKEN=your-public-token` in each checkout's git-ignored root `local.properties`. Gradle generates `mapbox_access_token`; never commit a token resource or package a secret (`sk.`) token. A public token is included in the installed app, so minimal scopes and usage monitoring still matter. Downloads from the Mapbox Maven repository need no secret token. The `ndk27` artifacts support Android’s 16 KB memory page requirement.

Mapbox mobile tokens cannot use Google’s package/SHA-1 restrictions; URL restrictions are intended for browser requests and must not be enabled for this Android token. For production, use a dedicated token per app/environment, minimal scopes, usage monitoring and rotation. Configure email usage notifications for the Maps SDK in the team’s Mapbox account settings, with monthly-active-user thresholds agreed by the team. These alerts do not stop billed usage; Mapbox has no configurable spending cap. Keep the separate Firebase/Google Cloud budget alert in place. See [Mapbox installation](https://docs.mapbox.com/android/maps/guides/install/), [token restrictions](https://docs.mapbox.com/accounts/guides/tokens/) and [usage notifications](https://docs.mapbox.com/accounts/guides/invoices/#spending-alerts).

Without a token, CI and local builds still compile and the map displays a setup state. The account/token must be provided before native map rendering, tile errors and billing can be verified on a device. The map observes `EventRepository.getUpcomingPublicEvents()` through `RepositoryMapEventSource`: public events update live, cached events remain usable offline, and Retry starts a new collection after a query error. The 14-day window is fixed when collection starts. Event-query Retry preserves a healthy map and its camera; renderer-only Retry keeps the event listener and preview. Individual resource failures after first map load leave cached areas and markers usable, with unavailable tiles potentially blank; only initial/style failures show the map Retry state. No fixture events are used in production.

**Map privacy:** Mapbox receives map resource requests for the viewed area. The renderer does not enable a location puck, GPS subscription or geofencing. Optional Mapbox telemetry and geofencing consent are set to disabled through the Compose attribution control when the map is created; SDK initialization/accounting requests still occur. Mapbox’s logo, attribution and telemetry preference dialog remain visible. No map tile prefetching or explicit offline region downloads are added; tiles already cached by the SDK remain usable. The shared app notice under Profile → Privacy & data is tracked in [#103](https://github.com/swent-polysocial/PolySocial/issues/103), with ownership and wording to be decided by the team.

Before every commit, check that `git status` never lists these files. If it does, fix `.gitignore` first and don't commit them.

### Google sign-in setup

In Firebase console → Authentication → Sign-in method, enable **Google** and select a support email. Keep **one account per email address** enabled.

In Project settings → Your apps → `com.polysocial`, register the **SHA-1 fingerprints** for every teammate's debug keystore, CI, and release/Play signing keystores (`./gradlew signingReport` for local fingerprints). Download a fresh, git-ignored `app/google-services.json` and ask the repository owner to update the CI secret. The config must include the **Web OAuth client ID**, which generates `default_web_client_id`; never hardcode it or commit keystores. If an older config has no Web OAuth client (`client_type: 3`), compilation fails with **unresolved reference `default_web_client_id`** before the runtime configuration check can run. Re-download the config and update the CI secret.

### CI APK signing setup

A repository maintainer provisions a dedicated CI signing key once. In a private temporary directory, generate a password-protected keystore and read its public SHA-1 fingerprint:

```bash
umask 077
keytool -genkeypair -storetype PKCS12 -keystore ci-apk.p12 -alias polysocial-ci \
  -keyalg RSA -keysize 3072 -validity 9125 -dname 'CN=PolySocial CI'
keytool -list -v -keystore ci-apk.p12 -alias polysocial-ci
```

Store the base64-encoded keystore in the GitHub Actions secret `CI_APK_KEYSTORE` and its password in `CI_APK_KEYSTORE_PASSWORD`. With the GitHub CLI, run:

```bash
base64 < ci-apk.p12 | gh secret set CI_APK_KEYSTORE --repo swent-polysocial/PolySocial
gh secret set CI_APK_KEYSTORE_PASSWORD --repo swent-polysocial/PolySocial
```

Enter the password at the prompt and remove the temporary keystore after both secrets are set. Keep the same key for future builds so Android accepts them as updates. GitHub hides saved secret values. If the key is lost or replaced, register the new SHA-1 in Firebase and have testers uninstall the previous APK once.

Only the final publishing step on `main` reads these secrets. It verifies the signed APK and prints its public certificate fingerprint.

Register the key's **SHA-1** under Firebase Project settings → Your apps → `com.polysocial`. Download the updated `google-services.json` to the git-ignored `app/google-services.json` and update the existing `GOOGLE_SERVICES` Actions secret with the base64-encoded file:

```bash
base64 < app/google-services.json | gh secret set GOOGLE_SERVICES --repo swent-polysocial/PolySocial
```

Check the first published APK on an Android phone with both Google and email sign-in. Install a second CI APK over it to verify the update.

## Downloading the CI APK

Milestone builds are attached to the [Releases](https://github.com/swent-polysocial/PolySocial/releases). For any other commit on `main`, use the CI artifact:

Sign in to GitHub (artifacts cannot be downloaded anonymously). After a successful push to `main`, open [CI - Test Runner](https://github.com/swent-polysocial/PolySocial/actions/workflows/ci.yml), choose the successful run for the desired commit on `main`, and download **polysocial.apk** directly from its **Artifacts** section. Open the downloaded APK on your phone to install it as-is, without unzipping.

If you are moving from an APK signed before [#383](https://github.com/swent-polysocial/PolySocial/issues/383) or from a developer's APK signed with a different key, uninstall it once before switching. Uninstalling removes the app's local data. Subsequent CI APKs install as updates. Install on an Android phone running Android 9 (API 28) or newer, allowing installation from that source when Android asks.

The APK is built from the commit whose checks, unit tests, device-test shards, Sonar analysis and release build all succeeded; the device-test shards build their own APKs. Artifacts expire after **7 days**, so use a recent successful `main` run for the Sprint review or M1. PR runs do not publish an APK. The one-day `ci-main-debug-apk` artifact is an internal handoff between jobs; download **polysocial.apk**.

For the CI APK to show the map, a repository maintainer must configure the GitHub Actions `LOCAL_PROPERTIES` secret with base64-encoded properties containing `MAPBOX_ACCESS_TOKEN=your-public-token` (see [Mapbox setup](#mapbox-setup)). A developer's local file is not available to GitHub's runner. An absent secret or token still allows a successful build, but the installed app shows the map setup state. Only use a public `pk.` token; never include a secret `sk.` token or a developer's SDK path in this CI configuration.

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

## Deploying to Firebase

Nothing deploys the Security Rules, the Firestore indexes or the Cloud Functions automatically: CI only tests them. When a PR that changes `firebase/firestore/firestore.rules`, `firebase/firestore/firestore.indexes.json` or `firebase/functions` is merged into `main`, **its author deploys it, or asks a teammate to: any team member can deploy**. They note in the PR that it was deployed. Always deploy from an up-to-date `main` (`git switch main && git pull` first), never from a feature branch, which could publish rules that aren't merged yet; the Firebase console (Firestore → Rules) then shows the new version with its publish time. Until then the app on `main` and production disagree: #220's rules and #214's index were missing from production until 2026-10-29.

Deploy with the [Firebase CLI](https://firebase.google.com/docs/cli) (`firebase login` first), and always with `--only`, since a plain `firebase deploy` deploys everything at once:

```bash
firebase deploy --only firestore:rules --project <project-id>     # firebase/firestore/firestore.rules
firebase deploy --only firestore:indexes --project <project-id>   # firebase/firestore/firestore.indexes.json
```

**Order depends on the change.** For public-profile deletion (#229), deploy `deleteAccount` first, then the Security Rules, then distribute the app. For #241 group cleanup, deploy its restrictive rules first, as described below under Cloud Functions. For #229 the order matters both ways: with the old function, deleting an account leaves the public profile behind and writes no `deletedAccounts/{uid}` marker; with the old rules, the new app's profile creation (which also writes `publicProfiles/{uid}`) is refused. The function uses the Admin SDK, so it works with either version of the rules.

For friendships (#357), deploy **`deleteAccount` first, then the rules**. Once the rules are live, any client can create friendships, and an account deleted while the previous `deleteAccount` still runs would keep them. The new function deletes friendships with the Admin SDK, so it is safe to deploy before the rules.

For one-to-one chats (#429), deploy **`deleteAccount` first, then #428's rules** (and #437's and #441's marker rules). Once they are live, students can create chats and delete-for-me markers, and an account deleted while the previous `deleteAccount` still runs would stay in its chats' `uids` and keep its markers. The new step finds nothing until then, so it is safe to deploy before the rules.

For check-ins (#467), deploy **the rules right after the merge**, before anyone runs or distributes an app built from `main`: cancelling a registration now also deletes the student's check-in in the same write, and with the old rules (no `checkIns` rule) that delete is refused, so every cancellation fails. Deploy the indexes too: they add the TTL policy on `checkIns.expiresAt`, which deletes each check-in 30 days after its event (usually within a day of `expiresAt`; the console shows it under Firestore → Time-to-live once active), and the `checkIns.uid` collection-group index that #472's `deleteAccount` step needs. The local emulator ignores TTL policies, so nothing tests the deletion itself. For the entrance code (#468), deploy **the rules right after the merge** as well (they add `checkInCode` and the QR branch of the check-in rule), then the indexes, which add the same TTL policy on `checkInCode.expiresAt`.

`<project-id>` is the `project_id` in your `app/google-services.json`. Functions deploy as described in [Cloud Functions](#cloud-functions). A new index takes a few minutes to build (Firebase console → Firestore → Indexes); until it's ready, the query that needs it fails. The local emulator doesn't need indexes.

## Backfilling allowedSections

Every event keeps `allowedSections`, the EPFL sections whose students can read it (`[]` for every section, #369). The Security Rules treat an event **without** the field as closed to students: a rule that accepted its absence would let a list query return the events of every section (see CONTEXT.md, Traps). Events created before the field existed don't have it, so a script gives it to them.

`firebase/functions/scripts/backfillAllowedSections.js` sets `allowedSections: []` on every event that doesn't have it as a list, each event in its own transaction, and leaves the others alone. It prints only counts, refuses to run without an explicit `--project` and `--yes`, and refuses a `demo-` project outside the emulator, like the attendee recount:

```bash
npm ci --prefix firebase/functions                     # once
gcloud auth application-default login                  # once; needs the Google Cloud CLI
node firebase/functions/scripts/backfillAllowedSections.js --project <project-id> --yes
```

**Deploy order of #369** (run each step before the next). The restriction is only real once the rules enforce it, so the Sections row of Create event (the picker PR) ships last:

1. Merge the PR with the rules and the section-aware query (#523) and this one. Right after the merge, from an up-to-date `main`, deploy the indexes (`firebase deploy --only firestore:indexes --project <project-id>`) and wait until they are built (Firebase console → Firestore → Indexes). The new upcoming-events query needs two composite indexes; until they exist it fails, so students who already have that app would see an error.
2. Run the backfill above. It works under the current rules, so nothing breaks while it runs.
3. Distribute the app (CI publishes the APK when the PR merges). The new query is accepted by the current rules, so students can already update.
4. Deploy the Security Rules (`firebase deploy --only firestore:rules --project <project-id>`). An app from before the section-aware query can no longer list the upcoming events (its query doesn't prove the section), so do it once most students have updated. Run the backfill once more right before, to catch the events an older app created since step 2.
5. **Final backfill and check, after the rules are active.** An older app can still create an event without `allowedSections` between the last run of step 4 and the moment the new rules take effect, and the section-aware query leaves such an event out. Once the rules are active the older app can no longer create one (the rules require `allowedSections` to be a list), so a run now is the last. Run the backfill again, then run it a **second time** to check: it must print `gave allowedSections to 0`. If the second run gives it to any event, something is still creating events without the field: do not go on, and find out what (an older build is the likely cause).
6. Only then merge the picker PR (#524). Before the rules are deployed, an event saved as restricted to some sections would still be readable by every student, and until the final check an event could still be missing the field, so the screen that offers the choice must not ship earlier.

Tests (CI doesn't run them; paste their output in a PR that changes the script):

```bash
node --test firebase/functions/scripts/backfillAllowedSections.test.js
firebase emulators:exec --project demo-polysocial --only firestore "node firebase/functions/scripts/backfillAllowedSections.emulator.test.js"
```
## Cloud Functions

Server code lives in `firebase/functions` (plain JavaScript, Node.js 22). It has six functions:

- the callable **`deleteAccount`** (region `europe-west6`): it deletes the signed-in user's private profile `users/{uid}` and public profile `publicProfiles/{uid}` together, with an empty `deletedAccounts/{uid}` marker that stops the user's still-valid ID token from recreating them or registering for an event, then their registrations for events (each lowering its event's `attendeeCount` by one, #405), their check-ins (#472, with the collection-group query `checkIns where uid == <UID>`), their friendships (#357), their place in their one-to-one chats and their delete-for-me markers (#429: the other student keeps a chat, with the deleted account's UID taken out of its `uids`; a chat nobody else is left in, because the other account is deleted too, is deleted with its messages; the account's `users/{uid}/hiddenChats` and `hiddenGroupChats` markers are deleted), then messages' sender account links and group memberships, then their Firebase Auth user, and returns `{ deleted: true }` only once all are gone. It refuses a sign-in older than 5 minutes (`failed-precondition`, details `recent-login-required`) and association accounts (`failed-precondition`, details `association`: they ask the PolySocial team through the privacy contact). Each step can be retried safely. Events the user created are kept. The Admin SDK bypasses the Security Rules, so the function itself checks who may delete what.
- the Firestore trigger **`deleteEventRegistrations`** (region `europe-west1`, because Eventarc delivers the `eur3` database's events only to `europe-west1` or `europe-west4`): when `events/{eventId}` is deleted, it deletes the event's registrations `events/{eventId}/attendees/*`, then its check-ins `checkIns/*` and its entrance code `checkInCode/current` (#472). It stops as soon as the event exists again with the same `createdBy`, since the registrations then belong to the event its organizer wrote back: the demo seed script deletes its events and writes them back under the same IDs and organizers. An event anyone else creates under that ID (a client chooses the ID; the rules make `createdBy` the creator's UID) doesn't stop it (#382). It is deployed with retries, so a failed run is retried until it succeeds.
- the Firestore triggers **`deleteEventGroups`** and **`deleteGroupMessages`** (#382, in the same region, with retries): when `events/{eventId}` is deleted, `deleteEventGroups` deletes the event's groups `events/{eventId}/groups/*` and their one-group-per-event markers `events/{eventId}/groupMembers/*`, stopping like `deleteEventRegistrations` when its organizer writes the event back; when a group is deleted (by its last member leaving, by `deleteAccount` taking out its last member, or by `deleteEventGroups`), `deleteGroupMessages` deletes that group's chat messages `.../messages/*`, and only those, even if a group is started again under the same ID (a client chooses it, so that student would otherwise read the old chat). A deleted account's own messages are anonymised by `deleteAccount`, not deleted, so the students who remain keep the conversation.
- the Firestore trigger **`syncFriendLists`** (#447, region `europe-west1`, with retries) reconciles both students' profile friend lists after each friendship write. It reads the current friendship and writes both entries with its acceptance time or deletes both entries in one transaction. Account deletion removes the source friendships and triggers the same cleanup.
- the scheduled **`deleteUnverifiedAccounts`** (region `europe-west6`, every day at 03:00 `Europe/Zurich`, #226): it deletes the email/password sign-ups (Firebase Auth accounts with an email and the `password` provider) whose email is still unverified more than **7 days** after they were created, so a mistyped or abandoned sign-up stops reserving an address its real owner may need. The period is one constant, `UNVERIFIED_ACCOUNT_RETENTION_DAYS` in `firebase/functions/deleteUnverifiedAccountsHandler.js`; the app's privacy notice states it too, under "Deleting your account" (`privacy_notice_deletion_body`), so change both together. It lists every Auth user in pages and never touches a verified account, an unverified one that is 7 days old or younger, or an account of another kind: the Admin SDK reports an account without any email as unverified too, so one without an email or without the `password` provider (Google, phone, anonymous, made by hand without a password, or of a provider added later) is kept, as is one whose email, providers or creation time can't be read. The accounts found are deleted in batches of 500: first any `users/{uid}` document stored for them, then the accounts (`deleteUsers`). An account that never verified can't have written anything (every Security Rule requires a verified email), so there is nothing else to clean up and no `deletedAccounts/{uid}` marker. "Unverified" is about the email only: an association whose email is verified but whose review is pending is never deleted. A failure doesn't stop the run: a page that can't be listed ends the listing, since the next page's token comes with the page, but the accounts found on the pages before it are still deleted, and a batch whose documents or accounts can't be deleted is counted and the next batch is tried. The run logs only counts and error codes, never an email or a UID, and ends as failed so that Cloud Scheduler and the function's logs show it. It isn't retried: the next day's run deletes what is left.

Install the packages once (Node.js **22.12 or newer**: `firebase-admin` depends on `jwks-rsa`, which needs `^20.19 || ^22.12 || >=23`), then run the unit tests, which use fakes and need no emulator:

```bash
npm ci --prefix firebase/functions
node --test firebase/functions/deleteAccountHandler.test.js firebase/functions/registrations.test.js firebase/functions/deleteUnverifiedAccountsHandler.test.js firebase/functions/groups.test.js
```

The emulator tests call `deleteAccount` over HTTP, like the app, delete events and groups to run the triggers (their shared setup and fakes are in `firebase/functions/testing`, which isn't deployed), check the `attendeeCount` that deleting registrations lowers (#405) and what the other student keeps of a one-to-one chat (#429), and run the cleanup of unverified accounts on accounts imported with their creation time, with the Auth, Firestore and Functions emulators (one file at a time, since each clears the emulators). The Functions emulator runs a scheduled function only through the Pub/Sub emulator, so it lists `deleteUnverifiedAccounts` as ignored: the tests call the cleanup's handler directly, and one calls the exported function in the test's own process, as Cloud Scheduler's HTTP request reaches it.

```bash
firebase emulators:exec --project demo-polysocial --only firestore,auth,functions "node --test --test-concurrency=1 firebase/functions/*.emulator.test.js"
```

Use the npm Firebase CLI (`npm install -g firebase-tools`, as CI does) with Node.js 22.12 or newer. The standalone CLI (`curl -sL firebase.tools | bash`) ignores the installed Node.js and runs the function with its own bundled Node 20, which fails to load the function (`ERR_REQUIRE_ESM`) before any test runs; the same applies to the deploy below, which loads the code locally first. The emulator warns if your Node.js isn't version 22; the tests still run on a newer one.

**Deploying is manual.** After a change to `firebase/functions` reaches `main`, its author deploys it, or asks a teammate to (see [Deploying to Firebase](#deploying-to-firebase); `firebase login` first). Cloud Functions need the Blaze plan with its budget alert; the project has been on it since 2026-11-05, and the alert is set up:

```bash
npm ci --prefix firebase/functions
firebase deploy --only functions --project <project-id>
```

Keep `--only functions`: a plain `firebase deploy` would also deploy the Security Rules and indexes. The app calls `deleteAccount` in `europe-west6`; if the region changes, change it in both `firebase/functions/deleteAccountHandler.js` (`REGION`) and the app. The first deploy of `deleteEventRegistrations` (#275) enables Eventarc and grants its service agent access; if it fails because those permissions are still propagating, run the same deploy again a few minutes later.

**After #447 merges, deploy the friend-list trigger and rules from up-to-date `main`.** Then run the backfill once, before releasing the friends-list UI (#450), to reconcile friendships accepted before the trigger was live. A teammate runs the script with their own Google Cloud sign-in.

```bash
npm ci --prefix firebase/functions
firebase deploy --only functions:syncFriendLists,firestore:rules --project <project-id>
gcloud auth application-default login
node firebase/functions/scripts/backfillFriendLists.js --project <project-id> --yes
```

The script requires an explicit project and confirmation. It uses the same transaction as the trigger, so it can be run again if interrupted. The Functions emulator tests exercise the trigger, account deletion and the backfill's reconciliation against demo data.

**After #472 merges, deploy the functions** (the same command, no rules or index; the `checkIns.uid` index the account step queries was deployed with #467): until then, deleting an account keeps its check-ins and cancelling an event keeps its check-ins and entrance code, until the TTL policy deletes them 30 days after the event. It updates `deleteAccount` and `deleteEventRegistrations` only.

**After #382 merges, deploy the functions** (the same command, no rules or index): until then, cancelling an event still leaves its groups, markers and messages, and a group its last member left keeps its messages. The deploy creates `deleteEventGroups` and `deleteGroupMessages` and updates `deleteEventRegistrations`, whose guard now also checks the event's creator; nothing else changes. They only clean up deletions made after they are live: what earlier cancelled events and emptied groups left behind stays until a one-off cleanup, which isn't built.

The first deploy of `deleteUnverifiedAccounts` (#226) enables the Cloud Scheduler API (`cloudscheduler.googleapis.com`) and creates the job `firebase-schedule-deleteUnverifiedAccounts-europe-west6` in `europe-west6`, with the same command and no other step; if it fails because the API was only just enabled, run the same deploy again a few minutes later. It is the project's only Cloud Scheduler job: a job costs USD 0.10 a month beyond the allowance of three free jobs per Google account. Its first run, at the next 03:00, deletes the email/password sign-ups that are already unverified and older than 7 days, so look at the Auth user list first; beyond 500 of them, the Admin SDK's limit of one `deleteUsers` call per second can refuse the later batches, and the following nights finish the backlog. Google Cloud console → Cloud Scheduler lists the job with the status of its last execution, and **Actions → Force run** starts it by hand.

Each deploy also stores the function's image in Artifact Registry. `europe-west6` deletes images older than 1 day, which keeps it within the free tier; `europe-west1` (the trigger's region) and any new region need the same policy (`firebase functions:artifacts:setpolicy --location <region> --days 1 --project <project-id>`), or the deploy asks for it and, without a terminal, fails after deploying the function.

**Group cleanup (#241):** deploy the rules that block deleted tokens from starting/joining groups or recreating membership markers before enabling the updated function. Cleanup then uses the existing collection-group index on `groups.memberUids`, rereads each group in a transaction and removes the user and their marker together, deleting an empty group as ordinary leave does. Failures preserve Auth and return an error so Retry completes the cleanup. Surviving groups keep their original opaque creator identifier. All messages sent by the deleted account, including former and orphaned chats, retain their text/time with an empty `senderId` and a deleted-account label. Deploy the new collection-group index on `messages.senderId` and wait for it to be ready before enabling the function. Deleted tokens lose chat read/send access immediately. Message text is not redacted, and cached copies on other devices may persist until refreshed or cleared. See `docs/Group-Chat-Deletion.md`.

**Not in CI yet:** CI runs neither the function's unit tests nor the Functions emulator, and doesn't deploy. Adding them needs a workflow change, which needs team approval, so run both commands above locally and paste their output in any PR that changes `firebase/functions`.

## Seeding demo data

For demos such as the M2 review (#243), `firebase/functions/scripts/seedDemo.js` fills the project with **fictional** data, so a demo never depends on whatever is in the database: a verified association ("Campus Sounds"), 8 public events over the next week around EPFL (2 of them the association's), 6 fictional students' public profiles and 3 open groups whose members are going. Nobody can sign in as any of them: demo with your own account. Only the venues are real places.

Every document it writes has an ID starting with `demo-`. Removing (and seeding, which first removes) deletes those, and each demo event **with everything under it, whoever wrote it**: a teammate's "I'm going" or group on a demo event goes too, since it only exists because of a fictional event. Nothing else in the project is read or changed. Dates are relative to when it runs (tomorrow to 6 days ahead, Zurich time), so seed again shortly before a demo; that also resets the scenario, so a demo can start with nobody going.

It uses the Admin SDK, which bypasses the Security Rules, with **your own Google sign-in**, never a key file. Run it yourself (never from CI or an agent), from the repository root, with an account that can edit the project:

```bash
npm ci --prefix firebase/functions                     # once
gcloud auth application-default login                  # once; needs the Google Cloud CLI
node firebase/functions/scripts/seedDemo.js --project <project-id> --yes            # write or refresh
node firebase/functions/scripts/seedDemo.js --project <project-id> --remove --yes   # delete it all
```

`<project-id>` is the `project_id` in your `app/google-services.json`. The script refuses to run without an explicit `--project` and `--yes`, and refuses a `demo-` project outside the emulator. If Google reports a missing quota project, run `gcloud auth application-default set-quota-project <project-id>` once.

Tests (CI doesn't run them, like the Cloud Functions tests; paste their output in a PR that changes the scripts). The emulator test needs the npm Firebase CLI, as in [Cloud Functions](#cloud-functions):

```bash
node firebase/functions/scripts/demoData.test.js
node firebase/functions/scripts/seedDemo.test.js
firebase emulators:exec --project demo-polysocial --only firestore "node firebase/functions/scripts/seedDemo.emulator.test.js"
```

The `scripts` folder is excluded from the Cloud Functions deploy in `firebase.json`.

## Recounting attendees

Every event keeps `attendeeCount`, the number of students going, which the app's register and cancel transactions change by one and the Security Rules check against the event's `capacity` (#372, built by #373). **Nobody edits `attendeeCount` by hand** (Firebase console included): a wrong value lets an event take too many students or refuses them too early, and a value stored as a decimal (`3.0`, which the console or a non-JavaScript client can write) locks the event until it is recounted. Code that writes registrations with the Admin SDK keeps it itself: `deleteAccount` lowers it with each registration it deletes, and the demo seed writes it (#405).

`firebase/functions/scripts/recountAttendees.js` sets every event's `attendeeCount` to the number of its registrations, each event in one transaction, and always writes it as a whole number. It prints how many events it recounted and how many had another count (it never prints an event or a student), and running it again changes nothing. Run it yourself (never from CI or an agent), with your own Google sign-in, like the demo seed:

```bash
npm ci --prefix firebase/functions                     # once
gcloud auth application-default login                  # once; needs the Google Cloud CLI
node firebase/functions/scripts/recountAttendees.js --project <project-id> --yes
```

It refuses to run without an explicit `--project` and `--yes`, and refuses a `demo-` project outside the emulator. **When to run it:** once, right after #373's Security Rules are deployed, so the events that already had registrations start from their real count, and again whenever a counter looks wrong.

**Deploy order of the capacity work** (Architecture Diagram, decision 21, "Rollout"):

1. After #405 is merged: deploy the functions (`deleteAccount` then lowers the counter; without a counter it lowers nothing, so this works with the current rules). Deployed on 2026-11-18.
2. Right after #373 is merged, in the same sitting: deploy its Security Rules, then run this recount once. CI publishes the new APK at the merge, and its "I'm going" is refused until the rules are deployed.
3. Then students update the app: an app from before #373 can no longer register once its rules are deployed.

Tests (CI doesn't run them; paste their output in a PR that changes the script):

```bash
node --test firebase/functions/scripts/recountAttendees.test.js
firebase emulators:exec --project demo-polysocial --only firestore "node firebase/functions/scripts/recountAttendees.emulator.test.js"
```


## Verifying an association

Only PolySocial admins who are Firebase/Google Cloud project owners perform this
manual operation. The Android app never writes `isAssociationVerified`.
Email verification and association approval are separate checks.

1. In the correct Firebase project, open **Authentication → Users**, locate the
   applicant and check that their email is verified. Copy their UID; never put
   applicant details in a public issue or test fixture.
2. Open **Firestore Database → users → {uid}**. Confirm `accountType` is the
   string `association` and the document belongs to that same UID. An association
   with an `@epfl.ch` email is valid and still receives only association access.
   Do not turn a student account into an association by editing its type.
3. In **Firestore Database → Rules**, compare the published rules with
   `firebase/firestore/firestore.rules` on current `main`, including the
   `associations/{uid}` owner access and verified name/type lock from #126
   (merged in #220). If they differ, deploy the rules from current `main`
   (any team member can) before continuing; see
   [Deploying to Firebase](#deploying-to-firebase).
   Open **Data → associations → {uid}** and check the name, type, about and
   optional website against independent official evidence. If either document
   is missing, ask the applicant to finish setup; do not create it by hand.
4. After accepting the request, add or edit **`isAssociationVerified`** in
   `users/{uid}` with Firestore type **boolean**, value **`true`**. The string
   `"true"` does not verify the association. Never add this field to
   `associations/{uid}` or to client-writable metadata.
5. Re-read both documents and confirm the UID, association name and type are
   still the ones reviewed. If anything changed during review, revoke approval
   and check again. Name/type are locked while approved.
6. Ask the applicant to restart the app (or sign out and back in). On current
   `main`, approval opens the existing app with the association's own Events
   list (#132). Once #140 is integrated, verified accounts get their own
   Events, Map and Organization tabs. Approval permits publishing and managing
   only their own events; it grants no student profile, group, chat or matching
   access.

To **undo verification**, set the same boolean to **`false`**. On the next app
start, #127's merged pending app shows pending review again. Server rules deny
new verified-only operations when they evaluate the revoked flag. Revocation
neither deletes events nor retracts data already cached. It also unlocks
name/type editing, so re-check identity before approving again.

## Project links

- **Figma mockups:** [M2](https://www.figma.com/design/7Gt7tmy1ikpNyNBh1P6QdY/App-Mockup?node-id=123-467), the team-agreed design (the page was "First proposal revamped" until M1; the M1 snapshot is linked from the wiki's Home)
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
