<!-- Contributors: Claude (drafted the README structure, Setup, Running and testing, Tech stack and Project links sections). -->

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
| Sign up and log in with an EPFL email (verified email required), profile with section and year | Sprint 1 |
| App shell: Events, Map, Chats and Profile tabs, one back stack per tab | Sprint 1 |
| Interactive map of upcoming public events, with straight-line distance when location is allowed | Sprint 1 |
| Create events: private (off the map, people ask to join) or public; verified associations get a badge | Sprint 1 |
| Sign in with Google (EPFL accounts only) | Planned |
| Events list with filters, event details, "My events" available offline | Planned |
| **Find a group:** matching on interests, section, year and common friends; group proposals | Planned |
| Group chat, with report and block | Planned |
| One-off check-in at the venue (GPS read once, or an entrance QR code) and opt-in location sharing with your group until the event ends | Planned |
| Shared event album, unlocked by check-in | Planned |
| Friends (by username or QR code), public or private profiles | Planned |
| Association accounts with members, verified manually | Planned |
| Event reminders (also offline) and in-app notifications | Planned |

**Privacy:** location is never tracked continuously. It is read once for a feature, or shared only with your group, only during the event, and only when you choose to. Profiles, photos, chats and locations are personal data under the Swiss nLPD, and you can delete your account and data.

**Offline:** events you've loaded, your registered events and their schedule, and reminders stay available offline. The map shows cached events on whatever map tiles are already cached.

## Tech stack

- **Kotlin** and **Jetpack Compose** (Material 3), **MVVM** with `StateFlow` UI state; dependency injection with **Hilt**
- **Firebase:** Authentication, Cloud Firestore (with offline persistence), Cloud Storage for photos, Local Emulator Suite for tests
- **Maps:** Google Maps SDK for now, Mapbox under consideration; **Nominatim / OpenStreetMap** for geocoding
- **Device:** GPS (one-off location), CameraX + ML Kit for QR codes, WorkManager for reminders
- **Quality:** JUnit, Robolectric, Kaspresso, JaCoCo coverage, SonarCloud, ktfmt, GitHub Actions CI

The design of the app is on the wiki's **[Architecture Diagram](https://github.com/swent-polysocial/PolySocial/wiki/Architecture-Diagram)** page: layers, navigation, data model, Security Rules and design decisions.

## Setup

The app needs two private files that are **not in the repository**. Both are git-ignored and must never be committed, because the repository is public.

| File | What it holds | How to get it |
|---|---|---|
| `local.properties` (repository root) | The Android SDK path: `sdk.dir=/path/to/Android/Sdk`. Later also the Maps API key. | Android Studio creates it when you open the project. Otherwise, write that one line yourself. |
| `app/google-services.json` | The Firebase configuration for `com.polysocial` | Firebase console → Project settings → *Your apps* → Android app `com.polysocial` → download `google-services.json`. Or ask a teammate. |

**Prerequisites:** Android Studio with the Android SDK (compile SDK 37), and JDK 17 or newer.

Then build with `./gradlew assembleDebug` (on Windows, `./gradlew.bat assembleDebug`). CI creates both private files from GitHub secrets.

Before every commit, check that `git status` never lists these files. If it does, fix `.gitignore` first and don't commit them.

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

The `demo-` project ID keeps everything on the local emulator, never a real Firebase project. CI doesn't run these tests yet, so paste their output in any PR that changes the rules.

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
