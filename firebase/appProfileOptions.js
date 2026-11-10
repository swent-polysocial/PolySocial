// Contributors: Claude (read the app's profile codes in the rules tests, #229, #230; moved here
// to share them with the demo data tests, and explained the regexes after review, #243).
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
  // `val SECTIONS: List<String> = listOf("AR", "CGC", ...)`, over several lines: `\s*` allows the
  // line break after `=`, and `([^)]*)` captures everything up to the closing `)`, so
  // sectionsList[1] is `"AR", "CGC", ...` (no code contains a `)`).
  const sectionsList = source.match(/val SECTIONS: List<String> =\s*listOf\(([^)]*)\)/);
  // `val YEARS: List<String> = (1..6).map { "BA$it" } + ...`, on one line: `(.*)` captures the
  // rest of the line after `= `, so yearsLine[1] is `(1..6).map { "BA$it" } + (1..4).map { ... }`.
  const yearsLine = source.match(/val YEARS: List<String> = (.*)/);
  if (!sectionsList || !yearsLine) throw new Error("ProfileOptions.kt changed shape: update this test");
  // Each quoted run of capital letters in that list, without its quotes: "AR" gives AR.
  const sections = [...sectionsList[1].matchAll(/"([A-Z]+)"/g)].map((match) => match[1]);
  // Each `(from..to).map { "PREFIX$it" }` range, such as `(1..6).map { "BA$it" }`: the groups
  // capture 1, 6 and BA (`\$` is a literal `$`, `\{` a literal `{`), and the range expands to BA1
  // to BA6, as Kotlin does.
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
  // Same shape as SECTIONS: everything between `listOf(` and its closing `)`.
  const list = source.match(/val INTERESTS: List<String> =\s*listOf\(([^)]*)\)/);
  if (!list) throw new Error("ProfileOptions.kt has no INTERESTS list: update this test");
  // Each quoted string in that list, without its quotes (interests are lower case, so `[^"]+`).
  const interests = [...list[1].matchAll(/"([^"]+)"/g)].map((match) => match[1]);
  if (interests.length === 0) throw new Error("Couldn't read INTERESTS in ProfileOptions.kt");
  return interests;
}

module.exports = { appInterests, appProfileOptions };
