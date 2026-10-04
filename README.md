# PolySocial
 
> Never go alone. Find a group to attend EPFL campus events with.
 
## Pitch
 
Many EPFL students, in particular first-years and exchange students, skip campus events not because they don't hear about them (Telegram, Instagram and posters already cover that) but because they have nobody to go with and don't want to show up alone.
 
PolySocial turns event discovery into group discovery. A student browses upcoming events on an interactive Lausanne map, taps **"Find a group"** on one, and is matched with a small group of other students attending the same event, based on shared interests, common friends, section and year. The group gets a chat to plan the outing, checks in together at the venue, and shares its photos in the event album.
 
The insight is that the barrier to campus life is social rather than informational: PolySocial solves *"who do I go with?"*. Students can also create private events (e.g. a study-together session) that do not show on the map until matches are found and approved.
 
## Features
 
### Split-app model
 
- **Firebase Firestore** for data storage
- **Firebase Authentication** to sign in as a student
- **Google Maps API** for the interactive map of events
### Multi-user support
 
- Every user authenticates with their EPFL account.
- Students can communicate with each other, describe events and comment on them.
- Associations can create their own accounts and manage their events.
### Sensor use
 
**GPS drives the group outing.**
 
- **Automatic check-in:** when members arrive at the venue during the event, they are checked in automatically, and the group chat shows who has arrived.
- **Find my group:** a view shows checked-in members on the venue map.
- **Privacy:** location is shared only with your group and only during the event.
- **Better matching:** verified attendance improves matching, since students who actually show up are favored.
- **Indoor fallback:** indoors, where GPS is unreliable, scanning a QR code at the entrance confirms presence instead.
**The camera builds on check-in:** only checked-in members can add photos to the event album.
 
### Offline mode
 
- Cached offline map, registered events and event schedule
- Event reminders delivered even without a connection
 


## Setup

<!-- Contributors: Claude (drafted the Setup section). -->

The app needs two private files that are **not in the repository**. Both are git-ignored and must never be committed, because the repository is public.

| File | What it holds | How to get it |
|---|---|---|
| `local.properties` (repository root) | The Android SDK path: `sdk.dir=/path/to/Android/Sdk`. Later also the Maps API key. | Android Studio creates it when you open the project. Otherwise, write that one line yourself. |
| `app/google-services.json` | The Firebase configuration for `com.polysocial` | Firebase console → Project settings → *Your apps* → Android app `com.polysocial` → download `google-services.json`. Or ask a teammate. |

Then build with `./gradlew assembleDebug` (on Windows, `./gradlew.bat assembleDebug`). CI creates both files from GitHub secrets.

Before every commit, check that `git status` never lists these files. If it does, fix `.gitignore` first and don't commit them.

## Design

Figma mockups: [PolySocial App Mockup](https://www.figma.com/design/7Gt7tmy1ikpNyNBh1P6QdY/App-Mockup?node-id=0-1)
