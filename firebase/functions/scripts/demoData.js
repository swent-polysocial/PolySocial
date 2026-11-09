// Contributors: Claude (the fictional demo data for the M2 review, #243).
"use strict";

// The fictional data seedDemo.js writes for a demo: a verified association, eight public events
// over the next week around EPFL, and three open groups of fictional students. Every document ID
// starts with DEMO_PREFIX, and only these documents are ever written or removed. The fields are
// the ones the app writes (FirestoreEventMapping.kt, FirestoreGroupMapping.kt, the profile and
// association repositories), so the app and the Security Rules read them like their own. Nothing
// here is a real person, address or organization; only the venues are real places.

const DEMO_PREFIX = "demo-";
const ZONE = "Europe/Zurich";

const ASSOCIATION = "demo-association";

/** Fictional students, with codes from ProfileOptions.kt and interests in INTERESTS order. */
const STUDENTS = [
  { id: "demo-student-1", name: "Léa Martin", section: "IN", year: "BA3", interests: ["music", "games", "tech"] },
  { id: "demo-student-2", name: "Noah Keller", section: "SC", year: "BA5", interests: ["sports", "outdoors", "tech"] },
  { id: "demo-student-3", name: "Inès Rossi", section: "AR", year: "MA1", interests: ["cinema", "photography", "arts"] },
  { id: "demo-student-4", name: "Jonas Weber", section: "GM", year: "BA1", interests: ["sports", "music", "parties"] },
  { id: "demo-student-5", name: "Sara Haddad", section: "SV", year: "MA2", interests: ["music", "cooking", "volunteering"] },
  { id: "demo-student-6", name: "Tim Nguyen", section: "IN", year: "BA3", interests: ["games", "tech", "startups"] },
];

/**
 * The events, each `day` days after today in Zurich, from `start` to `end` (local "HH:MM").
 * Starting tomorrow at the earliest and within six days keeps them all upcoming and inside the
 * window the Events tab and the map show, whenever the script runs.
 */
const EVENTS = [
  {
    id: "demo-event-study", by: "demo-student-1", category: "STUDY", day: 1, start: "14:00", end: "17:00", capacity: 20,
    title: "Analysis II exam prep", place: "Rolex Learning Center", at: [46.5184, 6.5681],
    description: "Going through last year's exam together. Bring your notes; we'll split into small tables.",
  },
  {
    id: "demo-event-jam", by: ASSOCIATION, category: "PARTY", day: 2, start: "20:30", end: "23:30", capacity: null,
    title: "Open-air jam session", place: "Satellite", at: [46.5206, 6.5655],
    description: "Bring an instrument or just come to listen. Open stage all evening.",
  },
  {
    id: "demo-event-volleyball", by: "demo-student-2", category: "SPORTS", day: 2, start: "18:00", end: "20:00", capacity: 12,
    title: "Beach volleyball after class", place: "UNIL-EPFL Sports Centre", at: [46.5185, 6.5793],
    description: "Casual games, all levels welcome. Teams are mixed on the spot.",
  },
  {
    id: "demo-event-film", by: "demo-student-3", category: "CULTURE", day: 3, start: "19:30", end: "22:00", capacity: null,
    title: "Film night: Swiss classics", place: "Rolex Forum", at: [46.5187, 6.5676],
    description: "Two short Swiss classics with subtitles, then a chat over drinks.",
  },
  {
    id: "demo-event-run", by: "demo-student-4", category: "SPORTS", day: 4, start: "17:30", end: "19:00", capacity: null,
    title: "Sunset run by the lake", place: "Parc du Denantou", at: [46.5094, 6.6506],
    description: "An easy 6 km along the lake, about 6 min/km. We wait for everyone.",
  },
  {
    id: "demo-event-cooking", by: "demo-student-5", category: "OTHER", day: 5, start: "18:30", end: "21:00", capacity: 8,
    title: "Cooking together: Lebanese dinner", place: "EPFL Esplanade", at: [46.5197, 6.5661],
    description: "We cook mezze together, then eat. Ingredients are shared; tell us about allergies.",
  },
  {
    id: "demo-event-vinyl", by: ASSOCIATION, category: "CULTURE", day: 5, start: "20:00", end: "22:00", capacity: 40,
    title: "Vinyl listening night", place: "SG building, EPFL", at: [46.5193, 6.5633],
    description: "Members bring their favourite records; we listen to one side each.",
  },
  {
    id: "demo-event-boardgames", by: "demo-student-6", category: "OTHER", day: 6, start: "19:00", end: "22:30", capacity: null,
    title: "Board games evening", place: "Le Giacometti", at: [46.5198, 6.5629],
    description: "Strategy and party games, from quick rounds to a long campaign.",
  },
];

/** Open groups (at most 3 of 4 places taken), the creator first. */
const GROUPS = [
  { id: "demo-group-jam", event: "demo-event-jam", members: ["demo-student-4", "demo-student-1", "demo-student-5"] },
  { id: "demo-group-volleyball", event: "demo-event-volleyball", members: ["demo-student-2", "demo-student-4"] },
  { id: "demo-group-study", event: "demo-event-study", members: ["demo-student-6"] },
];

/** Minutes Zurich is ahead of UTC at [date]. */
function zurichOffsetMinutes(date) {
  const name = new Intl.DateTimeFormat("en-US", { timeZone: ZONE, timeZoneName: "longOffset" })
    .formatToParts(date)
    .find((part) => part.type === "timeZoneName").value; // "GMT+01:00"
  const match = name.match(/GMT([+-])(\d{2}):(\d{2})/);
  if (!match) return 0;
  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return match[1] === "-" ? -minutes : minutes;
}

/** The moment it is [time] ("HH:MM") in Zurich, [day] days after [now]'s date in Zurich. */
function zurichTime(now, day, time) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", { timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit" })
      .formatToParts(now)
      .map((part) => [part.type, part.value]),
  );
  const [hour, minute] = time.split(":").map(Number);
  const wall = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day) + day, hour, minute);
  return new Date(wall - zurichOffsetMinutes(new Date(wall)) * 60 * 1000);
}

/**
 * The demo documents as `{ path, data }`, for a script running at [now]. The Admin SDK's types
 * come in as functions, so this stays plain data that tests can check without Firebase:
 * [geoPoint] (latitude, longitude), [timestamp] (a Date) and [serverTime] (the server's time).
 */
function buildDemoData({ now, geoPoint, timestamp, serverTime }) {
  const docs = [];
  docs.push({
    path: `users/${ASSOCIATION}`,
    data: {
      uid: ASSOCIATION,
      // .invalid is reserved: this address can never belong to anyone. Nobody signs in as it.
      email: "campus-sounds@demo.invalid",
      displayName: "",
      section: "",
      year: "",
      interests: [],
      accountType: "association",
      isAssociationVerified: true,
      createdAt: serverTime(),
    },
  });
  docs.push({
    path: `associations/${ASSOCIATION}`,
    data: {
      name: "Campus Sounds",
      type: "CLUB",
      about: "A fictional music club for the PolySocial demo: jam sessions, listening nights and concerts on campus.",
      website: "https://campus-sounds.invalid",
      createdAt: serverTime(),
    },
  });
  for (const student of STUDENTS) {
    docs.push({
      path: `publicProfiles/${student.id}`,
      data: {
        displayName: student.name,
        section: student.section,
        year: student.year,
        interests: [...student.interests],
      },
    });
  }
  for (const event of EVENTS) {
    docs.push({
      path: `events/${event.id}`,
      data: {
        title: event.title,
        description: event.description,
        category: event.category,
        location: geoPoint(event.at[0], event.at[1]),
        locationName: event.place,
        startTime: timestamp(zurichTime(now, event.day, event.start)),
        endTime: timestamp(zurichTime(now, event.day, event.end)),
        capacity: event.capacity,
        isPrivate: false,
        createdBy: event.by,
        organizerIds: [event.by],
        allowedUids: [event.by],
        isAssociationEvent: event.by === ASSOCIATION,
      },
    });
  }
  for (const group of GROUPS) {
    const eventPath = `events/${group.event}`;
    docs.push({
      path: `${eventPath}/groups/${group.id}`,
      data: { capacity: 4, memberUids: [...group.members], createdBy: group.members[0], createdAt: serverTime() },
    });
    for (const uid of group.members) {
      docs.push({ path: `${eventPath}/groupMembers/${uid}`, data: { groupId: group.id } });
      docs.push({ path: `${eventPath}/attendees/${uid}`, data: { uid, registeredAt: serverTime() } });
    }
  }
  return docs;
}

/** Every path [buildDemoData] writes; they don't depend on the time, so removal finds them all. */
function demoPaths() {
  const stub = () => null;
  return buildDemoData({ now: new Date(0), geoPoint: stub, timestamp: stub, serverTime: stub }).map(
    (doc) => doc.path,
  );
}

module.exports = { DEMO_PREFIX, buildDemoData, demoPaths };
