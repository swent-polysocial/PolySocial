// Contributors: Claude (the demo seed script's options, #243); Claude (Opus 5.5, Anthropic) (shared
// with the attendee recount, #405).
"use strict";

/**
 * The options of a script that writes to a Firebase project, in [argv]: `--project <id>` (always
 * required, never guessed), any of the boolean [flags] (such as `--remove`), and on a real project
 * an explicit `--yes`. Under the emulator (FIRESTORE_EMULATOR_HOST in [env]) only a `demo-`
 * project is accepted, and outside it a `demo-` project is refused, so the two can't be mixed up.
 * Returns `{ project, flags }`, with each flag's name (without `--`) set to whether it was passed.
 * Throws an Error explaining what's wrong.
 */
function parseProjectArgs(argv, env, flags = []) {
  let project = null;
  let yes = false;
  const passed = Object.fromEntries(flags.map((flag) => [flag, false]));
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--project") {
      const value = argv[i + 1];
      if (!value || value.startsWith("--")) throw new Error("--project needs a project ID");
      project = value;
      i++;
    } else if (arg === "--yes") yes = true;
    else if (arg.startsWith("--") && arg.slice(2) in passed) passed[arg.slice(2)] = true;
    else throw new Error(`Unknown option ${arg}`);
  }
  if (!project) throw new Error("Pass the Firebase project explicitly with --project <id>");
  const emulator = Boolean(env.FIRESTORE_EMULATOR_HOST);
  if (emulator && !project.startsWith("demo-")) {
    throw new Error("Under the emulator, use a demo- project (for example demo-polysocial)");
  }
  if (!emulator && project.startsWith("demo-")) {
    throw new Error("A demo- project only exists in the emulator: start it with firebase emulators:exec");
  }
  if (!emulator && !yes) {
    throw new Error(`This writes to the real project ${project}: add --yes to confirm`);
  }
  return { project, flags: passed };
}

module.exports = { parseProjectArgs };
