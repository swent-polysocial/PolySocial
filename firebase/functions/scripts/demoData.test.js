// Contributors: Claude (the fictional demo data's tests, written test-first, #243; trimmed to the
// safety and date checks after review).
"use strict";

// Unit tests, no emulator: node firebase/functions/scripts/demoData.test.js
// They check what can go wrong silently: a document outside the demo- IDs (seeding or --remove
// would then touch real data), paths that change with the date (--remove would miss old ones), the
// Zurich date arithmetic, and profile codes the app no longer offers. The data itself isn't
// re-asserted here.
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
// Top-level documents of a collection, such as "events/demo-event-jam" but not its groups.
const docsIn = (collection, now = NOW) =>
  build(now).filter(({ path }) => new RegExp(`^${collection}/[^/]+$`).test(path));

test("every document has only demo- IDs, so seeding and removing never touch real data", () => {
  for (const { path } of build()) {
    // In "events/demo-event-jam/groups/demo-group-jam" the IDs are the odd segments.
    const ids = path.split("/").filter((_, i) => i % 2 === 1);
    for (const id of ids) assert.ok(id.startsWith(DEMO_PREFIX), `${path}: ${id} isn't a demo ID`);
  }
});

test("the paths don't depend on when it runs, so --remove finds everything seeded earlier", () => {
  const later = new Date(NOW.getTime() + 40 * DAY_MS);
  assert.deepEqual(build(later).map((doc) => doc.path), build().map((doc) => doc.path));
  assert.deepEqual(demoPaths(), build().map((doc) => doc.path));
});

test("the events are upcoming within 7 days whenever the script runs", () => {
  const later = new Date(NOW.getTime() + 123 * DAY_MS + 5 * 60 * 60 * 1000);
  for (const now of [NOW, later]) {
    for (const { path, data } of docsIn("events", now)) {
      assert.ok(at(data.startTime) > now, `${path} starts before now`);
      assert.ok(at(data.startTime) - now < 7 * DAY_MS, `${path} starts after 7 days`);
      assert.ok(at(data.endTime) > at(data.startTime), `${path} ends before it starts`);
    }
  }
});

test("events start at the same Zurich time in winter and summer", () => {
  const zurichClock = (date) =>
    new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Zurich", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
      .format(date);
  const startClocks = (now) => docsIn("events", now).map(({ data }) => zurichClock(at(data.startTime)));
  const winter = startClocks(NOW);
  // Summer time (UTC+2) must not shift them by an hour.
  assert.deepEqual(startClocks(new Date("2027-06-14T08:00:00Z")), winter);
  // The study session is set to start at 14:00 Zurich time, not 14:00 UTC.
  assert.ok(winter.includes("14:00"), `unexpected start times ${winter}`);
});

test("public profiles use the section, year and interest codes the app offers", () => {
  const { sections, years } = appProfileOptions();
  const interests = appInterests();
  for (const { path, data } of docsIn("publicProfiles")) {
    assert.ok(sections.includes(data.section), `${path}: ${data.section}`);
    assert.ok(years.includes(data.year), `${path}: ${data.year}`);
    // Known codes, each once, in the app's order, as the rules require.
    assert.deepEqual(data.interests, interests.filter((code) => data.interests.includes(code)), path);
    assert.equal(new Set(data.interests).size, data.interests.length, path);
  }
});
