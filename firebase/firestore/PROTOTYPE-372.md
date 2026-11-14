<!-- Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372). -->

Prototype for #372 / #373, never merged: the Architecture Diagram's rule sketch (section 6) applied to the repository's rules, one test per row of its tables, #373's `register` and `cancel` modelled with the JS SDK (decision 21), bursts, the Admin SDK side and one variant of the rules per removed condition, all against the Firestore emulator. To be pushed as the branch `prototype/372-attendee-count`, on top of `main` (the four commits `main` gained after `01560354` don't touch `firebase/`).

Where each file goes in the repository tree:

| File | On this branch |
|---|---|
| `firebase/firestore/firestore.rules` | **replaces** `firebase/firestore/firestore.rules`: the repository's rules with the sketch applied, byte-identical to the document's sketch |
| `firebase/firestore/firestore.rules.test.js` | **replaces** `firebase/firestore/firestore.rules.test.js`: the existing suite with every registration write moved to the counted write and seeded registrations counted; `RULES_FILE` points it at a mutant |
| `firebase/firestore/capacity.rows.test.js` | new in `firebase/firestore/`: rows C1–C4, O1–O9, R1–R28 (but R24), X1–X13, L1–L2, the repository's answers P1–P9 and one note. #373 moves the rows into `firestore.rules.test.js`, the only file CI runs |
| `firebase/firestore/rest.rows.test.js` | new in `firebase/firestore/`: the decimal rows C5, O10 and R24 through the emulator's REST API, and one note; #373 moves them too |
| `firebase/firestore/repositoryModel.js` | new in `firebase/firestore/`: the JS SDK model of `register` (with its classification) and `cancel` that the P rows and the bursts use |
| `firebase/firestore/bursts.test.js` | new in `firebase/firestore/`: many students at once, and what a registration does to the transactions that read the event (about 2.5 min) |
| `firebase/firestore/mutants/make.py`, `run.sh` | new in `firebase/firestore/mutants/`; the `*.rules` that `make.py` writes next to them are generated: leave them out |
| `firebase/functions/attendeeCount.prototype.test.js` | new in `firebase/functions/`: `deleteAccount`'s step and the recount (both written in the test, for the Admin SDK issue), next to the unchanged `registrations.js` |

Run from the repository root on the branch, after `npm ci --prefix firebase` and `npm ci --prefix firebase/functions`:

```
firebase emulators:exec --project demo-polysocial --only firestore "node firebase/firestore/firestore.rules.test.js && node firebase/firestore/capacity.rows.test.js && node firebase/firestore/rest.rows.test.js && node firebase/functions/attendeeCount.prototype.test.js"
firebase emulators:exec --project demo-polysocial --only firestore "node firebase/firestore/bursts.test.js"
python3 firebase/firestore/mutants/make.py
firebase emulators:exec --project demo-polysocial --only firestore "EXISTING=1 firebase/firestore/mutants/run.sh"
```

`ONLY="b1*"` before `run.sh` runs one mutant.

Results on 2026-11-14 after the second review : the converted suite 513 of 513; the rows and answers 113 of 113 (94 rows, P1–P9 in 18 tests, 1 note); the REST rows 5 of 5 (4 rows, 1 note); the Admin SDK side 9 of 9; the bursts 14 of 14, twice; the 35 variants of the rules as the document reports (a12, e06 and e15 survive alone, their doubles d1–d3 fail; r1 and r2 fail 10 and 5 tests of the converted suite); the reviewer's boundary mutant (`countBefore() <= 1`, `b1` here and `r372b/revmutants/m3` run directly) fails X3 only.
