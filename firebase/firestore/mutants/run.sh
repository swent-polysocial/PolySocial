#!/usr/bin/env bash
# Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372; never merged).
# PROTOTYPE for #372 (never merged): runs the row tests against each mutant of the rules (make.py
# writes them next to this script) and prints which rows fail. Run inside
# `firebase emulators:exec`, from any folder.
#   ONLY="e12* d*"  limits the run to the mutants whose names match.
#   EXISTING=1      also runs the existing suite (converted to counted writes) against r1 and r2.
here="$(cd "$(dirname "$0")" && pwd)"
tests="$(dirname "$here")"
set -f
patterns=(${ONLY:-*})
set +f
for pattern in "${patterns[@]}"; do
  for rules in "$here"/$pattern.rules; do
    [ -e "$rules" ] || continue
    name=$(basename "$rules" .rules)
    caught=$( (RULES_FILE="$rules" node "$tests/capacity.rows.test.js"; RULES_FILE="$rules" node "$tests/rest.rows.test.js") 2>&1 \
      | grep -E "^✖ [A-Z]+[0-9]*[a-z]? " | sed -E 's/^✖ ([A-Za-z0-9]+) .*/\1/' | sort -uV | tr '\n' ' ')
    echo "MUTANT $name: ${caught:-SURVIVES the row tests}"
  done
done
if [ -n "$EXISTING" ]; then
  for name in r1-create-only-counted r2-create-no-own-checks; do
    failed=$(RULES_FILE="$here/$name.rules" node "$tests/firestore.rules.test.js" 2>&1 | grep -E "^ℹ fail " | sed 's/ℹ fail //')
    echo "MUTANT $name, existing suite: $failed tests fail"
  done
fi
