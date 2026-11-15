#!/bin/sh
# Contributors: Claude (Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged).
# Runs the chat suites against each mutant that make.py wrote; a mutant is caught when one of its
# suites fails. Run inside the Firestore emulator, from the repository root (PROTOTYPE-365.md).
cd "$(dirname "$0")/../../.." || exit 1
M=firebase/firestore/mutants-365
F=firebase/firestore
mkdir -p "$M/logs"
caught=0
survived=0
while read -r name suites; do
  result=""
  failed=0
  for suite in $(echo "$suites" | tr ',' ' '); do
    case $suite in
      old) file=chats.prototype.test.js ;;
      new) file=chats.deleting.prototype.test.js ;;
      group) file=chats.group.prototype.test.js ;;
    esac
    RULES_FILE="$PWD/$M/$name.rules" node "$F/$file" > "$M/logs/$name.$suite.log" 2>&1
    code=$?
    result="$result $suite=$code"
    [ "$code" -ne 0 ] && failed=1
  done
  if [ "$failed" -eq 1 ]; then caught=$((caught + 1)); echo "caught   $name:$result"
  else survived=$((survived + 1)); echo "SURVIVED $name:$result"; fi
done < "$M/list.txt"
echo "mutants: $((caught + survived)), caught $caught, survived $survived"
