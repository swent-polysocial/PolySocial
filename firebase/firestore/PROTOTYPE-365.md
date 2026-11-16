<!-- Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged; Opus 5.5: the group marker deleted when its owner leaves the group, and a one-to-one chat back once friends again, 2026-11-16). -->

Prototype for #365, **never merged**: the Security Rules of `docs/Architecture-Diagram.md` section 3.8 (one-to-one chats, unsend and delete for me in one-to-one and group chats) applied to the repository's rules, with a prototype of #338's `friendships` rules, the tests that checked them, a prototype of the step the design adds to `deleteAccount`, and one variant of the rules per new condition, all against the Firestore emulator. It is the branch `prototype/365-chat-rules`, three commits on top of `main`: the prototype, then the decisions of 2026-11-16, the group marker's delete rule (leaving a group deletes the marker) and the cases of a one-to-one chat back once friends again (no rule change). It is for whoever builds the Sprint 7 tasks to check the design against what ran and to start from. The rules tasks write the real rules and move the cases into `firestore.rules.test.js`, the only rules file CI runs.

| File | On this branch |
|---|---|
| `firebase/firestore/firestore.rules` | **replaces** the repository's rules: the chat blocks of section 3.8 (the main block and the group-messages block, byte-identical to the document's, indented by 4 spaces) and the `friendships` prototype. Only comments marked `PROTOTYPE` were added around them |
| `firebase/firestore/firestore.rules.test.js` | unchanged: the repository's suite, run against these rules |
| `firebase/firestore/chats.prototype.test.js` | new: the one-to-one chat (85 cases); `RULES_FILE` points it at a mutant |
| `firebase/firestore/chats.deleting.prototype.test.js` | new: unsend and delete for me in one-to-one chats, and a chat back once friends again (40 cases) |
| `firebase/firestore/chats.group.prototype.test.js` | new: unsend and delete for me in group chats, and leaving deleting the marker, also under a deletion marker or with a marker ID that can't be built (37 cases) |
| `firebase/firestore/mutants-365/make.py`, `run.sh` | new: one variant of the rules per new condition (33), and the run; the `*.rules`, `list.txt` and `logs/` they write are generated and ignored |
| `firebase/functions/chats.prototype.emulator.test.js` | new: what the unchanged `deleteAccountHandler` does to one-to-one messages, and the chat step the design adds, written in the test (8 tests) |
| `firebase/functions/chatsDeleting.prototype.emulator.test.js` | new: the unchanged handler on unsent messages and markers, and the marker cleanup the design adds, written in the test (6 tests) |

Run from the repository root on this branch, after `npm ci --prefix firebase` and `npm ci --prefix firebase/functions`:

```
firebase emulators:exec --project demo-polysocial --only firestore "node firebase/firestore/firestore.rules.test.js && node firebase/firestore/chats.prototype.test.js && node firebase/firestore/chats.deleting.prototype.test.js && node firebase/firestore/chats.group.prototype.test.js && node --test --test-concurrency=1 firebase/functions/chats.prototype.emulator.test.js firebase/functions/chatsDeleting.prototype.emulator.test.js"
python3 firebase/firestore/mutants-365/make.py
firebase emulators:exec --project demo-polysocial --only firestore "sh firebase/firestore/mutants-365/run.sh"
```

Expected (2026-11-16): the repository's suite 513 of 513; the one-to-one chat 85 of 85; unsend and delete for me 40 of 40 (one-to-one) and 37 of 37 (group); the `deleteAccount` side 14 of 14 (8 + 6). Mutants: 33, caught 31. The two that survive are kept on purpose, as section 3.8 says: `two-part-size` (`parts.size() == 2`: other part counts are refused by the join comparison or by an error) and `group-hide-exists` (`exists(group())`: `get()` on a missing group is an error, so a denial anyway). `group-unhide-before-write` is the one variant that doesn't replace its condition by `true`: it reads the group as it was before the write, to show why the delete rule needs `existsAfter` and `getAfter`. The leave cases mirror `leaveGroup` with the delete the decision adds, skipped when `groupMarkerId` can't build the ID; a marker ID over 1,500 bytes can't be checked here, since the emulator accepts a delete under it. A refused delete of a group marker logs an `evaluation error` beside its `false`: the emulator evaluates a delete's rule twice, once with neither the stored document nor the state after the write (the rule coverage shows both as errors), and allows the delete when the other evaluation is true. #232's `groupMembers` delete shows the same.
