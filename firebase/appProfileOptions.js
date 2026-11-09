// Contributors: Claude (read the app's profile codes in the rules tests, #229, #230; moved here
// unchanged to share them with the demo data tests, #243).
"use strict";

// The app's fixed profile codes, read from ProfileOptions.kt, so tests in firebase/ fail when the
// app's lists and theirs stop matching. Plain Node, no dependency.
const { readFileSync } = require("node:fs");
const path = require("node:path");

/**
 * The section and year codes the app offers, read from ProfileOptions.kt, so these tests fail if
 * the app and the rules' lists stop matching.
 */
function appProfileOptions() {
  const source = readFileSync(
    path.join(__dirname, "../app/src/main/java/com/polysocial/model/user/ProfileOptions.kt"),
    "utf8",
  );
  const sectionsList = source.match(/val SECTIONS: List<String> =\s*listOf\(([^)]*)\)/);
  const yearsLine = source.match(/val YEARS: List<String> = (.*)/);
  if (!sectionsList || !yearsLine) throw new Error("ProfileOptions.kt changed shape: update this test");
  const sections = [...sectionsList[1].matchAll(/"([A-Z]+)"/g)].map((match) => match[1]);
  const years = [...yearsLine[1].matchAll(/\((\d+)\.\.(\d+)\)\.map \{ "([A-Z]+)\$it" \}/g)].flatMap(
    ([, from, to, prefix]) =>
      Array.from({ length: Number(to) - Number(from) + 1 }, (_, i) => `${prefix}${Number(from) + i}`),
  );
  if (sections.length === 0 || years.length === 0) {
    throw new Error(`Couldn't read ProfileOptions.kt: ${sections.length} sections, ${years.length} years`);
  }
  return { sections, years };
}

/**
 * The interest codes the app offers (#230), in order, read from INTERESTS in ProfileOptions.kt, so
 * these tests fail if the app and the rules' lists stop matching.
 */
function appInterests() {
  const source = readFileSync(
    path.join(__dirname, "../app/src/main/java/com/polysocial/model/user/ProfileOptions.kt"),
    "utf8",
  );
  const list = source.match(/val INTERESTS: List<String> =\s*listOf\(([^)]*)\)/);
  if (!list) throw new Error("ProfileOptions.kt has no INTERESTS list: update this test");
  const interests = [...list[1].matchAll(/"([^"]+)"/g)].map((match) => match[1]);
  if (interests.length === 0) throw new Error("Couldn't read INTERESTS in ProfileOptions.kt");
  return interests;
}

module.exports = { appInterests, appProfileOptions };
