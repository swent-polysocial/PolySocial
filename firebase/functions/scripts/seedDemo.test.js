// Contributors: Claude (the demo seed's command-line safety checks, written test-first, #243).
"use strict";

// Unit tests, no emulator: node firebase/functions/scripts/seedDemo.test.js
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { parseArgs } = require("./seedDemo");

const REAL = {};
const EMULATOR = { FIRESTORE_EMULATOR_HOST: "127.0.0.1:8080" };

test("seeding a real project needs the project and an explicit --yes", () => {
  assert.deepEqual(parseArgs(["--project", "polysocial-adba0", "--yes"], REAL), {
    project: "polysocial-adba0",
    remove: false,
  });
  assert.throws(() => parseArgs(["--project", "polysocial-adba0"], REAL), /--yes/);
});

test("--remove is passed through, and also needs --yes on a real project", () => {
  assert.deepEqual(parseArgs(["--project", "polysocial-adba0", "--remove", "--yes"], REAL), {
    project: "polysocial-adba0",
    remove: true,
  });
  assert.throws(() => parseArgs(["--project", "polysocial-adba0", "--remove"], REAL), /--yes/);
});

test("the project is always required, never guessed", () => {
  assert.throws(() => parseArgs(["--yes"], REAL), /--project/);
  assert.throws(() => parseArgs([], EMULATOR), /--project/);
  assert.throws(() => parseArgs(["--project"], REAL), /--project/);
  assert.throws(() => parseArgs(["--project", "--yes"], REAL), /--project/);
});

test("the emulator needs no --yes, and only takes a demo- project", () => {
  assert.deepEqual(parseArgs(["--project", "demo-polysocial"], EMULATOR), {
    project: "demo-polysocial",
    remove: false,
  });
  assert.throws(() => parseArgs(["--project", "polysocial-adba0"], EMULATOR), /demo-/);
});

test("a demo- project outside the emulator is refused, since it can't be a real one", () => {
  assert.throws(() => parseArgs(["--project", "demo-polysocial", "--yes"], REAL), /emulator/);
});

test("an unknown option is refused instead of ignored", () => {
  assert.throws(() => parseArgs(["--project", "polysocial-adba0", "--yes", "--force"], REAL), /--force/);
});
