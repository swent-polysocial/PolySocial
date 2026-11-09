// Contributors: Claude (the fictional demo data's tests, written test-first, #243).
"use strict";

// Unit tests, no emulator: node firebase/functions/scripts/demoData.test.js
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { appInterests, appProfileOptions } = require("../../appProfileOptions");
const { DEMO_PREFIX, buildDemoData, demoPaths } = require("./demoData");

// Monday 9 November 2026, 10:00 in Zurich.
const NOW = new Date("2026-11-09T09:00:00Z");
const DAY_MS = 24 * 60 * 60 * 1000;

// Stand-ins for the Admin SDK's types, so the builder's output can be checked without it.
const types = {
  geoPoint: (latitude, longitude) => ({ geo: [latitude, longitude] }),
  timestamp: (date) => ({ at: date.toISOString() }),
  serverTime: () => ({ serverTime: true }),
};

const build = (now = NOW) => buildDemoData({ now, ...types });
const at = (value) => new Date(value.at);
const docsIn = (collectionPattern) =>
  build().filter(({ path }) => new RegExp(`^${collectionPattern}/[^/]+$`).test(path));
const byPath = () => new Map(build().map((doc) => [doc.path, doc.data]));

// The fields the app writes and reads for an event (Event.toFirestoreMap in FirestoreEventMapping.kt).
const EVENT_FIELDS = [
  "title", "description", "category", "location", "locationName", "startTime", "endTime",
  "capacity", "isPrivate", "createdBy", "organizerIds", "allowedUids", "isAssociationEvent",
];
const CATEGORIES = ["STUDY", "SPORTS", "CULTURE", "PARTY", "OTHER"];
const ASSOCIATION_TYPES = ["ASSOCIATION", "COMMISSION", "CLUB", "EPFL_UNIT"];
const COLLECTIONS = [
  /^users\/[^/]+$/,
  /^associations\/[^/]+$/,
  /^publicProfiles\/[^/]+$/,
  /^events\/[^/]+$/,
  /^events\/[^/]+\/(attendees|groups|groupMembers)\/[^/]+$/,
];

test("every document is in a collection the app uses, under a demo- ID", () => {
  for (const { path } of build()) {
    assert.ok(COLLECTIONS.some((pattern) => pattern.test(path)), `unexpected path ${path}`);
    const ids = path.split("/").filter((_, i) => i % 2 === 1);
    for (const id of ids) assert.ok(id.startsWith(DEMO_PREFIX), `${path}: ${id} isn't a demo ID`);
  }
});

test("no document is written twice", () => {
  const paths = build().map((doc) => doc.path);
  assert.equal(new Set(paths).size, paths.length);
});

test("the same moment builds the same data", () => {
  assert.deepEqual(build(), build());
});

test("the paths don't depend on when it runs, so --remove finds everything seeded earlier", () => {
  const later = new Date(NOW.getTime() + 40 * DAY_MS);
  assert.deepEqual(build(later).map((doc) => doc.path), build().map((doc) => doc.path));
  assert.deepEqual(demoPaths(), build().map((doc) => doc.path));
});

test("eight public events, all upcoming within the next 7 days, ending after they start", () => {
  const events = docsIn("events");
  assert.equal(events.length, 8);
  for (const { path, data } of events) {
    assert.deepEqual(Object.keys(data).sort(), [...EVENT_FIELDS].sort(), path);
    assert.equal(data.isPrivate, false, path);
    assert.ok(at(data.startTime) > NOW, `${path} starts before now`);
    assert.ok(at(data.startTime) - NOW < 7 * DAY_MS, `${path} starts after 7 days`);
    assert.ok(at(data.endTime) > at(data.startTime), `${path} ends before it starts`);
  }
});

test("the events stay upcoming whenever the script runs", () => {
  const later = new Date(NOW.getTime() + 123 * DAY_MS + 5 * 60 * 60 * 1000);
  for (const { path, data } of build(later).filter(({ path }) => /^events\/[^/]+$/.test(path))) {
    assert.ok(at(data.startTime) > later, `${path} starts before now`);
    assert.ok(at(data.startTime) - later < 7 * DAY_MS, `${path} starts after 7 days`);
  }
});

test("events start at the same Zurich time in winter and summer, at a plausible hour", () => {
  const zurichClock = (date) =>
    new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Zurich", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
      .format(date);
  const startClocks = (now) =>
    build(now)
      .filter(({ path }) => /^events\/[^/]+$/.test(path))
      .map(({ data }) => zurichClock(at(data.startTime)));
  const winter = startClocks(NOW);
  // Summer time (UTC+2) must not shift them by an hour.
  assert.deepEqual(startClocks(new Date("2027-06-14T08:00:00Z")), winter);
  for (const clock of winter) assert.ok(clock >= "09:00" && clock <= "21:00", `starts at ${clock} in Zurich`);
  assert.ok(winter.includes("14:00") && winter.includes("20:30"), `unexpected start times ${winter}`);
});

test("each event follows the app's limits, with its creator as organizer and reader", () => {
  for (const { path, data } of docsIn("events")) {
    assert.ok(CATEGORIES.includes(data.category), path);
    assert.ok(data.title.length >= 1 && data.title.length <= 80, path);
    assert.ok(data.description.length <= 5000, path);
    assert.ok(data.locationName.length >= 1 && data.locationName.length <= 100, path);
    assert.ok(data.capacity === null || data.capacity >= 2, path);
    assert.deepEqual(data.organizerIds, [data.createdBy], path);
    assert.deepEqual(data.allowedUids, [data.createdBy], path);
    const [latitude, longitude] = data.location.geo;
    // Lausanne and around: the map opens there.
    assert.ok(latitude > 46.45 && latitude < 46.6 && longitude > 6.5 && longitude < 6.75, path);
  }
});

test("exactly two events are the verified association's, with its badge", () => {
  const all = docsIn("events");
  const association = all.filter(({ data }) => data.isAssociationEvent);
  assert.equal(association.length, 2);
  for (const { data } of association) assert.equal(data.createdBy, "demo-association");
  for (const { data } of all.filter(({ data }) => !data.isAssociationEvent)) {
    assert.ok(data.createdBy.startsWith("demo-student-"));
  }
});

test("the association is verified and set up like the app's, with no real address", () => {
  const docs = byPath();
  const user = docs.get("users/demo-association");
  assert.deepEqual(Object.keys(user).sort(), [
    "accountType", "createdAt", "displayName", "email", "interests", "isAssociationVerified", "section", "uid", "year",
  ]);
  assert.equal(user.uid, "demo-association");
  assert.equal(user.accountType, "association");
  assert.equal(user.isAssociationVerified, true);
  assert.equal(user.displayName, "");
  assert.deepEqual(user.interests, []);
  // .invalid is reserved and can never belong to anyone.
  assert.match(user.email, /@[a-z0-9.-]+\.invalid$/);

  const association = docs.get("associations/demo-association");
  assert.deepEqual(Object.keys(association).sort(), ["about", "createdAt", "name", "type", "website"]);
  assert.ok(association.name.length >= 1 && association.name.length <= 60);
  assert.ok(association.about.length >= 1 && association.about.length <= 1000);
  assert.ok(ASSOCIATION_TYPES.includes(association.type));
  assert.match(association.website, /^https:\/\/[^\s]+\.invalid(\/|$)/);
});

test("public profiles hold only the four shared fields, with the app's codes, never an email", () => {
  const { sections, years } = appProfileOptions();
  const interests = appInterests();
  const profiles = docsIn("publicProfiles");
  assert.ok(profiles.length >= 4);
  for (const { path, data } of profiles) {
    assert.deepEqual(Object.keys(data).sort(), ["displayName", "interests", "section", "year"], path);
    assert.ok(data.displayName.length >= 1 && data.displayName.length <= 50, path);
    assert.ok(sections.includes(data.section), `${path}: ${data.section}`);
    assert.ok(years.includes(data.year), `${path}: ${data.year}`);
    // Known codes, each once, in the app's order, as the rules require.
    assert.deepEqual(data.interests, interests.filter((code) => data.interests.includes(code)), path);
    assert.equal(new Set(data.interests).size, data.interests.length, path);
  }
});

test("only students get public profiles; the association gets none", () => {
  for (const { path } of docsIn("publicProfiles")) assert.ok(path.startsWith("publicProfiles/demo-student-"));
  assert.equal(byPath().has("publicProfiles/demo-association"), false);
});

test("groups are open, like the app's, with every member going and holding the marker", () => {
  const docs = byPath();
  const groups = build().filter(({ path }) => /^events\/[^/]+\/groups\/[^/]+$/.test(path));
  assert.equal(groups.length, 3);
  for (const { path, data } of groups) {
    const [, eventId, , groupId] = path.split("/");
    assert.ok(docs.has(`events/${eventId}`), `${path}: no such event`);
    assert.deepEqual(Object.keys(data).sort(), ["capacity", "createdAt", "createdBy", "memberUids"], path);
    assert.equal(data.capacity, 4, path);
    assert.ok(data.memberUids.length >= 1 && data.memberUids.length < 4, `${path} isn't open`);
    assert.equal(data.createdBy, data.memberUids[0], path);
    assert.deepEqual(data.createdAt, { serverTime: true }, path);
    for (const uid of data.memberUids) {
      assert.ok(docs.has(`publicProfiles/${uid}`), `${path}: ${uid} has no public profile`);
      assert.deepEqual(docs.get(`events/${eventId}/groupMembers/${uid}`), { groupId }, `${path}: ${uid}'s marker`);
      assert.deepEqual(
        docs.get(`events/${eventId}/attendees/${uid}`),
        { uid, registeredAt: { serverTime: true } },
        `${path}: ${uid} isn't going`,
      );
    }
  }
});

test("every marker and registration belongs to a group member of that event", () => {
  const docs = byPath();
  for (const { path, data } of build()) {
    const marker = path.match(/^events\/([^/]+)\/groupMembers\/([^/]+)$/);
    if (marker) {
      const group = docs.get(`events/${marker[1]}/groups/${data.groupId}`);
      assert.ok(group && group.memberUids.includes(marker[2]), path);
    }
    const attendee = path.match(/^events\/([^/]+)\/attendees\/([^/]+)$/);
    if (attendee) assert.equal(data.uid, attendee[2], path);
  }
});
