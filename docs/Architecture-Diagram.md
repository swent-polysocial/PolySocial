<!-- Contributors: OpenAI Codex (Google authentication architecture for #106; preserved contributor acknowledgments during rebase). -->
<!-- Contributors: OpenAI Codex (Sprint 2 architecture, navigation, registration/reminder design and open questions, #139; documented Mapbox renderer/setup decisions and production/privacy open questions, #50); Claude (drafted this page and its diagrams from the README, the Scrum Board and the issue descriptions; association accounts without an EPFL email; Create Event design update, #44; ViewModel rule wording; Security Rules state after #35; the profile step's year question, #34; association badge from the profile, #45; synced the place-name and sign-in decisions with `CONTEXT.md`). -->

How PolySocial is built, as we currently envision it. The page follows the [Android App Architecture guide](https://developer.android.com/topic/architecture/intro): a **UI layer** (Compose screens and ViewModels), a **domain layer** of pure Kotlin logic, and a **data layer** of repositories in front of Firebase, the map and geocoding services, and the device sensors.

This diagram is meant to **stay ahead of the code and drive development**. When a sprint task changes the design, update this page in the same PR. The Markdown source is versioned in the repository at `docs/Architecture-Diagram.md`, and this wiki page mirrors it.

## Contents

1. [Architecture overview](#1-architecture-overview)
2. [Navigation map](#2-navigation-map)
3. [Feature slices](#3-feature-slices)
4. [Key flows](#4-key-flows)
5. [Data model](#5-data-model)
6. [Security Rules](#6-security-rules)
7. [Design decisions](#7-design-decisions)
8. [Backlog traceability](#8-backlog-traceability)

## Legend

| Colour | Meaning |
|---|---|
| 🟩 Green | In the **Sprint 2 scope**, including its Sprint 1 foundations. Green means planned for the sprint, not necessarily implemented or merged. Names and contracts come from the linked issues. |
| 🟦 Blue | In the **Product Backlog**. Planned, and the names are proposals until an issue fixes them. |
| ⬜ Grey, dashed | **Later or undecided**. Needs a team decision or its own issue first. |
| 🟨 Yellow | **External service or device API**. |

Arrows read "uses" or "calls". A dashed arrow is a planned or optional dependency.

**Dependency rule.** Screens talk only to their ViewModel. ViewModels talk to repositories and to domain logic. Only repository implementations import Firebase, HTTP or location APIs. The map SDK is a UI component isolated in `MapboxRenderer`, which renders ViewModel state and does not query event data. The domain layer imports nothing from Android or Firebase.

## 1. Architecture overview

![PolySocial architecture overview: UI, domain, data and external layers](images/architecture-overview.png)

The source is [docs/images/architecture-overview.svg](images/architecture-overview.svg), a plain SVG kept next to the PNG. Both source and export are now versioned in the main repository; the wiki mirrors them. Edit it in any vector editor (or as text), then export the PNG again.

**How to read it.**
- Every screen that holds state has exactly one ViewModel. The app shell (app bar, bottom bar and `NavHost`, #41) has none: its only state is navigation, which the `NavController` owns, and a ViewModel copy of it could get out of sync. The ViewModel exposes a single immutable UI-state `StateFlow` with loading, empty, success and error variants, and receives user actions as function calls.
- Repositories are Kotlin interfaces with a Firebase (or device) implementation and a `Fake…` implementation for tests. ViewModels get them through their constructor, injected by **Hilt** (`@HiltViewModel`). Tests swap in the fakes with Hilt's test modules.
- **Offline:** Firestore offline persistence is the only cache (no Room, no custom sync). Loaded events, registered events and their schedule stay readable offline. Writes such as chat messages are queued and synced on reconnect. Reminders are scheduled on the device. The map shows cached events on tiles the Maps SDK already cached, with **no tile prefetching**. Sign-up and log-in need a network and show a clear error without one.
- **Map provider:** Mapbox is approved (#50), replacing Google Maps. Its SDK is a UI component isolated in `MapboxRenderer`; `MapScreen` renders markers from `MapViewModel` state and never queries data itself. The public `pk.` token comes from each checkout's ignored `local.properties` through a generated string resource, with an empty-token setup state. One signup token is approved for initial development; a dedicated token with only `styles:read`/`fonts:read` is required before production. The token value never belongs in architecture documents or tracked resources. Firebase event data flows through `RepositoryMapEventSource`, adapting #49's live public-event repository without backend SDK imports in the ViewModel. Cached events remain usable, and cancellation releases the repository listener.

## 2. Navigation map

App-start routing (#32) decides between the authentication flow and the main app. The main app is a four-tab shell (#41). Each tab has its own back stack (#42) and starts in the shared loading or placeholder state (#43).

### 2.1 Signing in

```mermaid
flowchart TB
  classDef s1 fill:#d3f9d8,stroke:#2b8a3e,color:#000
  start(["App starts"]) --> gate{"Routing, see 4.3"}:::s1
  gate -- "not signed in" --> welcome["Welcome"]:::s1
  welcome --> signup["Sign Up"]:::s1
  welcome --> login["Log In"]:::s1
  login <-- "Sign up / Log in instead" --> signup
  gate -- "unverified" --> verify["Verify Email"]:::s1
  signup -- "created" --> verify
  login -- "unverified" --> verify
  login -- "verified" --> gate
  verify -- "Continue" --> gate
  gate -- "no profile yet" --> profile["Profile step"]:::s1
  profile -- "saved" --> main
  profile -- "Back: sign out" --> welcome
  gate -- "ready" --> main(["Main app: 4 tabs"]):::s1
```

### 2.2 Main app (one back stack per tab)

```mermaid
flowchart TB
  classDef s1 fill:#d3f9d8,stroke:#2b8a3e,color:#000
  classDef pb fill:#dbe4ff,stroke:#364fc7,color:#000
  events["Events: list · search · filters<br/>#105, #114, #115"]:::s1
  map["Map: markers · preview<br/>#50, #51"]:::s1
  create["Create Event<br/>#46"]:::s1 --> picker["Pin + explicit place search<br/>#111"]:::s1
  picker -- "coordinates + place name" --> create
  create -- "created / View event" --> detail["Shared Event detail<br/>#112"]:::s1
  events -- "event card" --> detail
  map -- "preview Details" --> detail
  detail --> attend["I'm going / Cancel<br/>#121, #122"]:::s1
  attend --> remind["Optional reminder 1 h before<br/>#120, #123"]:::s1
  profile["Profile"]:::s1 --> privacy["Privacy & data<br/>#103"]:::s1
  privacy --> notice["Full dated notice<br/>#103"]:::s1
  profile --> logout["Confirm Log out<br/>#108, #109"]:::s1
  logout --> welcome["Welcome: empty back stack<br/>#74"]:::s1
  profile -.-> friends["Friends · QR · matches"]:::pb
  chats["Chats list · chat"]:::pb
```

**Integration:** #112 replaces the Map detail placeholder with one shared screen and ViewModel class. Each entry tab registers its own event-ID route (the helper proposed in #157 is `Tab.eventDetailRoute`), so Events, Map and Create Event entry points return to their own tab stack without changing the selected tab. Unbuilt actions (Find a group, download data, attendance-based matching and account deletion) show a not-available Snackbar; they do not open unfinished screens. Privacy's full notice works offline. Logout must also cancel the previous account's reminders and avoid exposing its cached data to the next account (#109).


Notes:
- **Back behaviour (#39, #42).** Back pops the current tab's stack first. At the root of the Map, Chats or Profile tab it goes to the **Events** (home) tab, and at the Events root it exits the app. This is Android's standard bottom-navigation behaviour. Switching tabs restores each tab's screen and scroll position (#38).
- **Create Event.** A "+" button on the Events and Map tabs opens it. After creating, an "Event created" confirmation offers *View event* (the new event's detail) and *Back to map*, as in the Figma (#44).
- **Event detail.** Until the real screen exists, the Map tab's "View details" opens a placeholder route (#50).

## 3. Feature slices

Each feature in the Sprint 2 scope, from screen to data source. The Sprint 1 foundation slices remain below; section 3.6 describes the planned Sprint 2 additions. The Product Backlog features are already drawn in the [overview](#1-architecture-overview), so they are described in text only.

### 3.1 Authentication and profile (Sprint 1)

```mermaid
flowchart TB
  classDef s1 fill:#d3f9d8,stroke:#2b8a3e,color:#000
  classDef ext fill:#fff3bf,stroke:#e67700,color:#000

  screens["Sign Up · Verify Email · Log In screens"]:::s1
  vm["<b>per-screen auth ViewModels</b><br/>Idle · Loading · SignedUp · Verified<br/>ResendThrottled · StillUnverified<br/>WrongCredentials · Error"]:::s1
  val["EPFL email check<br/><i>domain</i>"]:::s1
  auth["<b>AuthRepository</b><br/>signUp · logIn · logOut · currentUser<br/>sendVerificationEmail<br/>reloadAndCheckVerified"]:::s1
  prof["<b>UserProfileRepository</b><br/>createProfile · getProfile<br/>updateProfile"]:::s1
  fa[("Firebase Auth")]:::ext
  fs[("Firestore<br/>users/{uid}")]:::ext

  screens --> vm
  vm --> val
  vm --> auth
  vm --> prof
  auth --> fa
  prof --> fs
```

- **Result types (#30–#32).** `signUp` returns success, invalid domain, already in use or network error. `logIn` adds wrong credentials. `sendVerificationEmail` has a distinct *throttled* failure for Firebase's too-many-requests error, which the UI shows differently from a real error.
- **Profile (#34).** `UserProfile` has at least `uid`, `email` and `createdAt`. #45 adds `accountType` (`student` or `association`, default `student`) and `isAssociationVerified` (default `false`). The document ID is always the Auth UID, never a client-generated ID. The profile is created **exactly once, at the first verified entry** (Verify Email "Continue", or app-start routing finding a verified user without a profile), because the Security Rules only allow writes from verified accounts. If account creation fails, no profile is written. If the profile write fails, the user sees an error state. Fields other students may see live in a separate `publicProfiles/{uid}` document (see [5.2](#52-collections-and-key-fields)).
- **Email verification.** Every student account must have a verified `@epfl.ch` email, and the Security Rules check this whatever the sign-in method. **Associations** have no EPFL email, so they sign up with their own verified email, and they get no access beyond their own `users/{uid}` until a PolySocial admin verifies them (decision 11). Sprint 1 uses email/password, where Firebase marks the email unverified until the student clicks the link we send, hence the Verify Email screen. Google sign-in (#106) delivers already-verified emails, so those users skip that screen. The rules would not change.
- **Log out (#8, #32).** Logging out clears the session, and the next launch routes to Log In.

### 3.2 Map (Sprint 1)

```mermaid
flowchart TB
  classDef s1 fill:#d3f9d8,stroke:#2b8a3e,color:#000
  classDef ext fill:#fff3bf,stroke:#e67700,color:#000

  mapS["Map screen<br/>markers · preview card"]:::s1 --> mapVM["<b>MapViewModel</b><br/>Loading · Empty · Success · Error<br/>permission granted / denied"]:::s1
  mapS -. renders .-> maps[("Map SDK<br/>Mapbox")]:::ext
  mapVM --> dist["Haversine distance<br/><i>domain</i>"]:::s1
  mapVM --> ev["<b>EventRepository</b><br/>getUpcomingPublicEvents"]:::s1
  mapVM --> loc["<b>LocationService</b><br/>permission · one-off location"]:::s1
  ev --> fs[("Firestore<br/>events")]:::ext
  loc --> gps[("Android approximate location<br/>network provider")]:::ext
```

- **Map (#50, #51).** The map is centred on Lausanne. Location permission is requested once, on first entry to the Map tab. If it's denied, the map stays fully usable without distance badges. Distance is labelled *straight-line*, not walking time. Route-based distances are out of scope.
- **Upcoming events query (#49).** The query **must** include `isPrivate == false`. Security Rules are not filters, so a query that could return a private event is rejected as a whole. Combining equality on `isPrivate` with a range on `startTime` needs a composite index, which should be versioned in `firestore.indexes.json` next to the rules.

### 3.3 Create Event (Sprint 1)

```mermaid
flowchart TB
  classDef s1 fill:#d3f9d8,stroke:#2b8a3e,color:#000
  classDef pb fill:#dbe4ff,stroke:#364fc7,color:#000
  classDef ext fill:#fff3bf,stroke:#e67700,color:#000

  createS["Create Event screen<br/>form · map-pin location"]:::s1 --> createVM["<b>CreateEventViewModel</b><br/>Editing · Saving · Created · Failed"]:::s1
  createVM --> valid["Event validation<br/><i>domain</i>"]:::s1
  createVM --> ev["<b>EventRepository</b><br/>createEvent"]:::s1
  createVM -. "association name, later" .-> prof["<b>UserProfileRepository</b>"]:::s1
  ev -- "creator's badge" --> prof
  createVM --> picker["Location picker ViewModel<br/>pin · explicit search, #111"]:::s1
  picker --> geo["<b>Geocoding service</b><br/>explicit place search"]:::s1
  picker --> loc["<b>LocationService</b><br/>optional one-off approximate fix"]:::s1
  ev --> fs[("Firestore<br/>events · users")]:::ext
  prof --> fs
  geo -.-> nom[("Geocoding service<br/>Nominatim by default")]:::ext
```

- **Event model (#45).** `id`, `title` (at most 80 characters), `description` (may be blank, at most 5000), `category` (Study, Sports, Culture, Party or Other; an unknown stored category reads as Other), `location` (lat/lng), `startTime`, `endTime` (nullable, after `startTime`), `capacity` (nullable, at least 2), `isPrivate`, `createdBy`, `organizerIds`, `allowedUids` and `isAssociationEvent`. `createdBy` is always the authenticated UID and is always in `organizerIds` and `allowedUids`. At creation the repository makes the creator the only organizer and allowed reader and sets `isAssociationEvent` from the creator's profile, ignoring the form's values. The Security Rules enforce this again (#47). An end time earlier than the start time on the form means the next day.
- **Create Event access (#46).** **Anyone can create an event** and becomes its **organizer**. An event can have one or several student organizers (`organizerIds`). Only a verified association (`accountType == "association"` and `isAssociationVerified`) publishes under its name: its events get `isAssociationEvent` and show a verified badge. An unverified association account can't create events or add members until it's verified, so the form has no access-denied state. Address search appears in the Figma task (#44), but the Sprint 1 build task only requires a map pin (#46), so the pin-and-search picker is in Sprint 2 (#111). Geocoding stays with the approved Nominatim service and its usage policy; choosing Mapbox for map rendering does not add a geocoding API.
- **Create Event screen (#44, #46).** The form follows the Figma (section 04): a date with a start time and an **optional** end time, which tells check-in and "Find my group" when the event is over. An end time earlier than the start time means the next day, shown as "+1 day" under the end field (Figma "Create event · overnight"). Inline errors cover a missing title, a title over 80 characters, a description over 5000, a past date, an end time equal to the start time, a missing location and a capacity below 2, and the *Create* button stays disabled while they remain. Offline, `EventRepository.createEvent` returns `NetworkError` without starting a write (Firestore would queue it and never fail), and the ViewModel shows the error with *Try again*, keeping the form. If location access is denied, the location picker still works with search and the map pin.

### 3.4 Groups, matching and chat (Product Backlog)

- **Matching starts on the client.** Following the risk plan, matching starts as a simple deterministic heuristic in a pure Kotlin module and runs on the client. Group creation and joining go through a **Firestore transaction**, so two students can't take the last spot at the same time. We revisit a Cloud Function only if fairness or cheating becomes a problem. The module would move unchanged, and only the caller would change.
- **Real-time screens.** The matches list (#28) and chat (#25) use Firestore snapshot listeners exposed as `Flow`.
- **Chat type.** Each group gets a group chat. A one-to-one chat is simply a two-member group, so there is one chat model and one set of rules.
- **Moderation-ready.** Messages carry `senderId` and `createdAt`, and the rules are written per operation, so report/block and rate limiting can be added later without a rule rewrite.

### 3.5 At the venue and social features (Product Backlog)

- **Check-in (#22).** A one-off location check against the venue during the event window. A QR code at the entrance is the indoor fallback. Only checked-in members can upload to the album (README).
- **Find my group (#20).** Locations are shared **only with the group and only during the event** (README). Each member's position lives in `groups/{groupId}/locations/{uid}`. It is written only during the event window, readable only by group members, and deleted when the event ends. There is no location history.
- **Album.** Photos are compressed and resized on the device, then stored in Cloud Storage. A Firestore document holds each photo's metadata. Storage Security Rules enforce size and type limits and check-in membership. Thumbnails are cached.
- **Reminders (#21, #36).** WorkManager schedules them when the student registers, so they fire offline and survive reboots. The notification permission (Android 13+) is requested at that moment.
- **QR codes (#17, #22).** Scanned with CameraX + ML Kit barcode scanning. Payloads are typed strings such as `polysocial://checkin/{eventId}/{token}` and `polysocial://friend/{uid}`, validated by a pure parser.

### 3.6 Sprint 2 additions (#103, #106–#123)

This is the design to build against, not a claim that every component is already merged. Implementation status remains on the Scrum Board and the linked PRs.

| Feature | UI / domain | Repository or service boundary | Data and failures |
|---|---|---|---|
| Google sign-in (#106, #107) | Welcome / Log in → their ViewModels; verified EPFL account result | `AuthRepository.signInWithGoogle`, Credential Manager inside the repository | Firebase Auth; cancel is quiet, non-EPFL accounts are signed out immediately, typed network/unexpected failures. Provider choice and console setup must be confirmed before release. |
| Pin and place search (#111) | Create Event → picker ViewModel; confirm coordinates and place name | Geocoding service interface and fake; reuse `LocationService` for optional centering and the isolated Mapbox renderer | Nominatim is the approved search provider: submitted searches only, custom User-Agent, caching and at most 1 request/second. Failed/offline search retains the pin; denied location opens on Lausanne. |
| Event detail (#112) | Shared detail ViewModel, category styles, local date/time and one-off distance | `EventRepository.getEvent(id)` is a live `Flow`, decoded by hand with `eventFromFirestore` | Existing event read rules; deleted or unreadable is not found, network errors offer Retry, cached content remains visible offline. Optional `locationName` describes the chosen event venue, not a location history. |
| Filtering (#114, #115) | `EventFilter` + pure `filterEvents(events, filter, userLocation)`; Events / Map ViewModels | Filter already loaded `getUpcomingPublicEvents` results | No new Firestore query or index. Case/accent-insensitive title search; categories OR, criteria AND; clock-injected date range [start, end); missing location ignores the distance criterion. |
| Attendance (#121, #122) | Detail: register/cancel; pure capacity checks coordinated by its ViewModel | `RegistrationRepository.register(eventId)`, `cancel(eventId)`, `observeMyRegistrations()`; Firestore implementation + fake | Planned `events/{eventId}/attendees/{uid}`. Register is idempotent; full events are refused atomically; offline writes fail instead of queuing. Schema and capacity enforcement still need the confirmation below. |
| Reminder (#120, #123) | Registered student's explicit opt-in; pure start minus 1 hour calculation | `ReminderScheduler` interface + fake; WorkManager only in its implementation | Unique work per event on the device, no new Firestore collection or FCM. No scheduling once the reminder time has passed; denial restores Off. Off/cancel/logout removes work; notification taps open shared detail. |
| Privacy / logout (#103, #108, #109) | Profile entry, dated offline notice and unbuilt-action Snackbar; logout confirmation | Notice requires no new data access; logout uses `AuthRepository.logOut()` | Notice describes only collected data and actual access. Export, attendance-based matching and in-app deletion remain unavailable; the private manual-deletion contact must be confirmed. Logout/cache isolation needs the #109 team's decision. |

**Sprint Planning decisions** recorded in [#141](https://github.com/swent-polysocial/PolySocial/pull/141): only the creator is an organizer until Public profiles provides a way to find co-organizers; reminders are opt-in, not automatic; association screens move to Sprint 3; an eventual Cancel event deletes it rather than introducing a cancellation status; every unbuilt action reports its availability. This document records the design without changing Authentication or Security Rules.

## 4. Key flows

Each flow shows who calls whom, top to bottom. "App" is the screen together with its ViewModel.

### 4.1 Sign up (#30)

```mermaid
%%{init: {"sequence": {"mirrorActors": false, "actorMargin": 30, "width": 135, "actorFontSize": 15, "messageFontSize": 15, "noteFontSize": 14}}}%%
sequenceDiagram
  autonumber
  actor S as Student
  participant App as Sign Up screen
  participant AR as AuthRepository
  participant FA as Firebase Auth
  S->>App: email + password
  alt not an @epfl.ch email
    App-->>S: error, nothing sent
  else EPFL email
    App->>AR: signUp()
    AR->>FA: create account
    alt failed
      App-->>S: "already in use" or network error
    else created
      App->>AR: sendVerificationEmail()
      App-->>S: go to Verify Email
    end
  end
```

No profile is written yet. The Security Rules only accept writes from verified accounts.

### 4.2 Verify email, then create the profile (#31, #34)

```mermaid
%%{init: {"sequence": {"mirrorActors": false, "actorMargin": 30, "width": 135, "actorFontSize": 15, "messageFontSize": 15, "noteFontSize": 14}}}%%
sequenceDiagram
  autonumber
  actor S as Student
  participant App as Verify Email screen
  participant AR as AuthRepository
  participant PR as UserProfileRepository
  S->>App: taps Continue
  App->>AR: reloadAndCheckVerified()
  Note over AR: also refreshes the ID token
  alt not verified yet
    App-->>S: "still not verified"
  else verified
    App->>PR: getProfile()
    opt first verified entry
      App->>PR: createProfile(), once
    end
    App-->>S: enter the main app
  end
```

### 4.3 App start routing (#32, #74)

```mermaid
flowchart TD
  classDef s1 fill:#d3f9d8,stroke:#2b8a3e,color:#000
  start(["App starts"]) --> q1{"Signed in?"}
  q1 -- no --> welcome["Welcome screen"]:::s1
  q1 -- yes --> q2{"Email verified?"}
  q2 -- no --> verify["Verify Email screen"]:::s1
  q2 -- yes --> load["Loading your profile…<br/>(App start · loading)"]:::s1 --> q3{"Profile exists?"}
  q3 -- no --> create["Profile step:<br/>createProfile(), once"]:::s1
  main["Main app, Events tab"]:::s1
  create -- "saved" --> main
  create -- "Back: sign out" --> welcome
  q3 -- yes --> main
  q3 -- "load failed" --> err["Couldn't load your profile<br/>+ Retry, never a blank screen"]:::s1
  err -- "Retry" --> load
```

### 4.4 Map: public events and distances (#49–#51)

```mermaid
%%{init: {"sequence": {"mirrorActors": false, "actorMargin": 30, "width": 135, "actorFontSize": 15, "messageFontSize": 15, "noteFontSize": 14}}}%%
sequenceDiagram
  autonumber
  actor S as Student
  participant App as Map screen
  participant ER as EventRepository
  participant LS as LocationService
  S->>App: opens Map tab
  App->>ER: getUpcomingPublicEvents()
  Note over ER: public, upcoming
  ER-->>App: events (cache if offline)
  App-->>S: markers, or empty state
  opt location permission granted
    App->>LS: current location, once
    App-->>S: distance on each card
  end
  S->>App: taps a marker
  App-->>S: preview card
```

### 4.5 Find a group (Product Backlog, proposal)

```mermaid
%%{init: {"sequence": {"mirrorActors": false, "actorMargin": 30, "width": 135, "actorFontSize": 15, "messageFontSize": 15, "noteFontSize": 14}}}%%
sequenceDiagram
  autonumber
  actor S as Student
  participant App as Event detail
  participant MR as MatchRepository
  participant ME as Matching engine
  participant GR as GroupRepository
  S->>App: taps Find a group
  App->>MR: load requests
  App->>ME: propose(me, candidates)
  ME-->>App: ranked proposals
  App-->>S: shows proposals
  S->>App: accepts one
  App->>GR: join (transaction)
  App-->>S: group + group chat
```

## 5. Data model

### 5.1 How the collections relate

```mermaid
flowchart LR
  classDef s1 fill:#d3f9d8,stroke:#2b8a3e,color:#000
  classDef pb fill:#dbe4ff,stroke:#364fc7,color:#000
  classDef ext fill:#fff3bf,stroke:#e67700,color:#000

  users["users/{uid}<br/>private"]:::s1
  pub["publicProfiles/{uid}<br/>visible to others"]:::pb
  events["events/{eventId}"]:::s1
  reg["events/{eventId}/attendees/{uid}<br/>planned #121"]:::s1
  reminders["Device reminder state<br/>WorkManager, #123"]:::s1
  photos["photos"]:::pb
  groups["groups/{groupId}"]:::pb
  sub["members · messages<br/>check-ins"]:::pb
  mreq["matchRequests"]:::pb
  friends["friendships"]:::pb
  storage[("Cloud Storage<br/>photo files")]:::ext

  users --- |"same uid"| pub
  users -->|"organizes"| events
  events --> reg
  reg -. opt-in .-> reminders
  events --> photos
  photos -.-> storage
  events --> groups
  groups --> sub
  users --> mreq
  users --> friends
```

### 5.2 Collections and key fields

| Collection | Key fields | Status |
|---|---|---|
| `users/{uid}` | `uid`, `email`, `displayName`, `section`, `year`, `createdAt`, `accountType`, `isAssociationVerified` | 🟩 #34, #45 |
| `events/{eventId}` | `title`, `description`, `category`, `location`, `locationName?` (#112), `startTime`, `endTime?`, `capacity?`, `isPrivate`, `createdBy`, `organizerIds`, `allowedUids`, `isAssociationEvent` | 🟩 #45 |
| `publicProfiles/{uid}` | `username`, `displayName`, `section`, `year`, `interests` (always visible), `visibility` (public or private), `bio` | 🟦 new issue |
| `events/{eventId}/attendees/{uid}` | student document ID and registration metadata (exact schema to confirm) | 🟩 S2 #121; no separate top-level registrations collection |
| Device reminder state | event ID, title and start time for a local notification; unique work, opt-in | 🟩 S2 #123; no Firestore collection |
| `matchRequests/{id}` | `eventId`, `fromUid`, `toUid`, `status` | 🟦 #26, #27 |
| `groups/{groupId}` + `members`, `messages`, `checkIns` | `eventId`, `capacity` · `senderId`, `text`, `createdAt` · `method` (gps or qr). A one-to-one chat is a two-member group | 🟦 #14, #22, #25 |
| `groups/{groupId}/locations/{uid}` | position, `updatedAt`. Only during the event, deleted after | 🟦 #20 |
| `friendships/{id}` | `uidA`, `uidB`, `status` | 🟦 #16 |
| `events/{id}/photos/{photoId}` | `storagePath`, `uploaderId`, `createdAt`. The file itself is in Cloud Storage. | 🟦 album |

**Profiles are split, Instagram-style.** `users/{uid}` is private to its owner. `publicProfiles/{uid}` is what other students see. Section, year and interests are always visible because matching needs them. A **public** profile also shows friends, events attended, photos and the full bio to everyone. A **private** profile shows those only to approved connections.

## 6. Security Rules

Rules live in `firebase/firestore/firestore.rules` and are tested against the Firebase Local Emulator Suite. **Main today** allows each account's own `users/{uid}` (#35), plus public/private and own-association event reads (#52). Event writes and every subcollection, including attendees, remain denied until their reviewed rules land. The rows below describe the planned policy as well as the current reads; this documentation does not grant access.

| Collection | Read | Create | Update / delete | Issue | Status |
|---|---|---|---|---|---|
| *every rule* | requires `isEpflUser()`: signed in, `email_verified == true`, email ends with `@epfl.ch` | | | #33 | 🟩 S1 |
| *association branch* | a verified association (`email_verified == true`, `accountType == "association"`, `isAssociationVerified` set by an admin), **only** on its own association, members and events. Added with its first use, after #35 removed the catch-all rule | | | #47 | 🟩 S1 |
| `users/{uid}` | own document only | own document only | own document only | #35 | 🟩 S1 |
| | **Only exception to *every rule*:** any account with a verified email reaches its own `users/{uid}`, but never sets `isAssociationVerified` (create or update), and can't change `uid` (which must match the document ID) or `accountType` after creation. That is all an unverified association can reach | | | #35 | 🟩 S1 |
| `events/{id}`, public | any EPFL user | any EPFL user or verified association, `createdBy == auth.uid` and in `organizerIds`. `isAssociationEvent` only for a verified association | organizers only | #47, #52 | 🟩 S1 |
| `events/{id}`, private | `auth.uid in allowedUids` (organizers, members, approved match requesters) | as above | organizers only | #52, #23 | 🟩 S1 |
| `events/{eventId}/attendees/{uid}` | own attendance; attendee list only for the event's organizers | verified EPFL student, own UID, readable event; no association registration | student deletes only own document; no writes to another student's attendance | #121 | 🟩 S2 planned; currently denied |
| `publicProfiles/{uid}` | any EPFL user. Fields beyond the public set only for public profiles or approved connections | owner only | owner only | new issue | 🟦 PB |
| `groups/**`, `messages` | group members only | members, rate-limit ready | sender only | #25 | 🟦 PB |
| `matchRequests` | sender and recipient | sender | recipient responds | #26, #27 | 🟦 PB |
| Storage `events/{id}/album/**` | event group members | checked-in members, size and type limits | uploader | album | 🟦 PB |

Every PR that changes rules follows the process in `AGENTS.md`: "Changes Security Rules" in the description, allowed and denied emulator tests, and explicit reviewer sign-off.

## 7. Design decisions

- **Google sign-in (#106):** both entry buttons use Credential Manager through the auth repository. Invalid domains are rejected before Firebase account creation; Firebase validates the token and verified exact `@epfl.ch` identity before profile access. Provider conflicts preserve the existing account and require its original sign-in method; no email-only merge.


Decided by the team on 2026-10-03, with 13 and 14 revised on 2026-10-04 after review, and 5 and 11 revised on 2026-10-06. The issues are being updated to match.

| # | Topic | Decision |
|---|---|---|
| 1 | Profile creation | Exactly once, at the first verified entry (Verify Email "Continue" or app start), because the rules only accept verified accounts |
| 2 | Who creates events | Anyone. The creator becomes the organizer, and an event can have several organizers. Verified associations get a badge |
| 3 | Profiles | Private `users` plus `publicProfiles`, with public or private visibility. Section, year and interests are always visible |
| 4 | Private events | `allowedUids` access list, checked by the rules |
| 5 | Create Event entry | "+" button on the Events and Map tabs. Afterwards, an "Event created" confirmation with *View event* and *Back to map*, as in the Figma (changed on 2026-10-06) |
| 6 | Matching | On the device: a pure module plus a Firestore transaction. Cloud Functions only if fairness or cheating becomes a problem |
| 7 | Find my group | `groups/{id}/locations/{uid}`, only during the event, members only, deleted afterwards |
| 8 | Reminders | Opt-in WorkManager reminder 1 h before the start; Android 13+ permission on first opt-in; Off, cancellation and logout remove unique work (#123) |
| 9 | QR codes | CameraX + ML Kit. Typed `polysocial://checkin/…` and `polysocial://friend/…` payloads, pure parser |
| 10 | Section-exclusive events | Deferred. Later `allowedSections` on events, checked in the rules |
| 11 | Association verification | Associations have no EPFL email and sign up with their own. A PolySocial admin verifies them by hand (`isAssociationVerified` in the Firebase console, documented in the README); no access before that, except their own `users/{uid}`. Once verified, an association only sets up its association, manages its members and their roles, and publishes and manages its own events (no student profiles, groups, chats or matching). In-app admin flow later, not a current priority (changed on 2026-10-06) |
| 12 | One-to-one chat | A two-member group (one chat model) |
| 13 | Dependency injection | **Hilt** (changed after review). It is Android's recommended DI library, scales as repositories grow, and Hilt test modules swap in the fakes cleanly. The cost is some setup and slower builds |
| 14 | Back button | At a non-home tab root, go to the Events tab. At the Events root, exit. This is Android's standard pattern (changed after review) |

**Sprint 2 decisions:** sign-in is email/password and Google only, not Microsoft, which would need EPFL's admin to whitelist the app, while Google goes through EPFL's SSO (#106, #107, PR #197 review); events store the picked place's name as an optional `locationName` of at most 100 characters (rules #102, app #151; blank for older events); Nominatim remains the approved explicit-search geocoder; one shared detail screen replaces the Map placeholder, using each entry tab's event-ID route; co-organizers wait for Public profiles; associations move to Sprint 3; unbuilt actions show a Snackbar. Sources: #103, #106, #111, #112 and the Sprint Planning record #141.

**Open questions** (add new ones here and in `CONTEXT.md`):
- **Mapbox production account:** who adds billing, which usage alert applies, and when does a dedicated token replace the signup token? Mapbox bills separately from Google Cloud. Needed before release.
- **Shared privacy notice (#103):** Mapbox receives map requests for the viewed area; Mehdi owns Profile → Privacy & data; the private deletion contact and final wording still need confirmation before release.
- **Association members and event drafts:** Figma has them, but there is no data model yet. How does an association find and add a student as a member, and what can each role do? Drafts are not in the `Event` model.
- **Events without an end time:** `endTime` is optional, but check-in and Find my group only work during the event. Which time window applies when it is not set?
- **Attendance schema/capacity (#121):** confirm `events/{eventId}/attendees/{uid}` and its fields before implementation. How can a student's registration atomically enforce capacity while other attendees and event writes remain organizer-only? Agree the transaction/rules design without widening private reads or organizer write access.
- **Logout/reminders (#109, #123):** confirm account-scoped reminder cancellation and cache isolation before another account enters.
- **Year field values:** the profile step offers BA1–BA6 and MA1–MA4 under "Year" (as in Figma and #34), but these are EPFL semesters, and there is no option for exchange or PhD students. Rename to "Semester" and/or add options?

## 8. Backlog traceability

Every Scrum Board item mapped to the components it touches.

<details>
<summary>Show the table (every Scrum Board item → components)</summary>

| Item | Epic | Components | Status |
|---|---|---|---|
| #6 Sign up, #5 | Authentication | Sign Up screen, per-screen auth ViewModels, AuthRepository, email validation | 🟩 via #29, #30, #33 |
| #7 Log in | Authentication | Log In screen, app-start routing, AuthRepository | 🟩 via #29, #32 |
| #8 Log out | Authentication | Profile tab, AuthRepository.logOut | 🟦 (logOut built in #32) |
| #9 Data binding | Authentication | UserProfileRepository, `users/{uid}`, rules | 🟩 via #34, #35 |
| #37 Bottom navigation | App Shell | navigation, app bar | 🟩 via #41 |
| #38 Scroll position kept | App Shell | per-tab back stacks | 🟩 via #42 |
| #39 Back navigation | App Shell | per-tab back stacks | 🟩 via #42 |
| #40 Placeholder state | App Shell | shared Loading / Empty state | 🟩 via #43 |
| #12 Event creation | Event Creation | Create Event screen, CreateEventViewModel, EventRepository.createEvent, rules | 🟩 via #44–#47 |
| #10 Browse events, #11 | Event Discovery | Map tab, MapViewModel, getUpcomingPublicEvents, LocationService, distance | 🟩 via #48–#51 |
| #23 Hide private events | Event Discovery | `isPrivate`, query filter, events read rule | 🟩 via #49, #52 |
| #13 Filter events | Event Discovery | Events / Map filters, `EventFilter`, `filterEvents` | 🟩 S2 #113–#115 |
| #18 List of chosen events | — | My events, `RegistrationRepository`, attendees | 🟩 S2 #121, #122; My events reuse follows its own issue |
| #36 Offline registered events | — | Firestore offline cache, My events | 🟦 |
| #19 Section-exclusive event | — | later: `allowedSections` on events, checked in rules | ⬜ deferred |
| #14 Group for an event | Groups & Matchmaking | Find a group, MatchingViewModel, matching engine, GroupRepository | 🟦 |
| #24 Match students together | Groups & Matchmaking | matching engine (domain) | 🟦 |
| #26 Accept or decline match | Groups & Matchmaking | proposals screen, MatchRepository | 🟦 |
| #27 Match with other students | Groups & Matchmaking | Match button, MatchRepository | 🟦 |
| #28 Real-time list of matches | Groups & Matchmaking | Profile matches list, snapshot listener | 🟦 |
| #15 Ping friends | Groups & Matchmaking | FriendRepository, notifications | ⬜ |
| #25 Chat | Chat | Chats tab, ChatViewModel, ChatRepository, `messages` | 🟦 |
| #16 Adding friends | — | Friends screen, FriendRepository, `username` | 🟦 |
| #17 Meeting people by QR | — | QrScanner, QR payload parsing, FriendRepository | 🟦 |
| #22 Automatic check-in | — | check-in policy, LocationService, QR fallback, GroupRepository | 🟦 |
| #20 Find my group | — | group venue map, `groups/{id}/locations` | 🟦 |
| #21 Event notification | — | `ReminderScheduler`, pure reminder time, WorkManager, local notifications | 🟩 S2 #120, #123 |
| #103 Privacy notice | Profile | Privacy & data, full dated notice, unavailable actions | 🟩 S2 |
| #106, #107 Google sign-in | Authentication | `AuthRepository.signInWithGoogle`, Credential Manager, entry ViewModels | 🟩 S2 |
| #108, #109 Logout | Authentication | Profile confirmation, `AuthRepository.logOut`, empty auth stack, cache isolation | 🟩 S2 |
| #110, #111 Location picker | Event Creation | picker ViewModel, Mapbox renderer, geocoding service, place name | 🟩 S2 |
| #112 Event detail | Event Discovery | shared screen / ViewModel, per-tab event-ID routes, `EventRepository.getEvent` | 🟩 S2 |
| #113–#115 Filtering | Event Discovery | filter sheet, `EventFilter`, `filterEvents`, loaded public events | 🟩 S2 |
| #120–#123 Registration / reminders | Attendance | attendees, `RegistrationRepository`, `ReminderScheduler`, notification permission | 🟩 S2 |
| #137–#139 M1 delivery | Tooling / architecture | APK artifact, wiki, M1 snapshot, current architecture | 🟩 S2 |
| #124–#131, #140 Associations | Associations | association repository / Organization tab / event management | 🟦 Sprint 3; no student-flow dependency |
| #1, #53, #55 | — | project setup, PR template, CI. No product architecture impact | — |

</details>
