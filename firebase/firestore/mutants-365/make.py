# Contributors: Claude (Opus 5.5, Anthropic) (prototype of the #365 chat rules, never merged).
"""Writes one variant of firebase/firestore/firestore.rules per new condition of the #365 chat
rules, with that condition replaced by `true`, next to this file (<name>.rules), and list.txt with
"<name> <suites>" for run.sh. The conditions are found in the two blocks of
docs/Architecture-Diagram.md section 3.8 as the rules file holds them (indented by 4 spaces).
Suites: old = chats.prototype.test.js, new = chats.deleting.prototype.test.js,
group = chats.group.prototype.test.js."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RULES = os.path.join(HERE, "..", "firestore.rules")

MAIN_HEADER = ("    // PROTOTYPE (#365, never merged): from here to the association block, the main rules block of\n"
               "    // docs/Architecture-Diagram.md section 3.8, indented by 4 spaces.\n")
MAIN_END = "    // An association's public profile (#126)"
GROUP_START = "    match /events/{eventId}/groups/{groupId}/messages/{messageId} {\n"
GROUP_END = "\n    }\n"

OTO = "match /users/{uid}/hiddenChats/{pairId} {"
GRP = "match /users/{uid}/hiddenGroupChats/{chatId} {"
FWD = "      && request.resource.data.hiddenUpTo >= resource.data.hiddenUpTo;"
U1 = ("    allow update: if isOfThePair()\n        && areFriends()\n"
      "        && resource.data.senderId == request.auth.uid\n")
# (name, block "main" or "group", anchor or None, condition as written, replacement, suites)
MUTANTS = [
    ("two-part-size", "main", None, "return parts.size() == 2", "return true", "old,new,group"),
    ("two-part-first", "main", None, "      && parts[0] != ''", "      && true", "old,new,group"),
    ("two-part-join", "main", None, "      && id == parts[0] + '~' + parts[1];", "      && true;", "old,new,group"),
    ("pair-sorted", "main", None, "      && pair[0] < pair[1]", "      && true", "old,new"),
    ("pair-member", "main", None, "      && pair.hasAny([member]);", "      && true;", "old,new"),
    ("owner-student", "main", None, "  return isStudent()\n      && request.auth.uid == uid", "  return true\n      && request.auth.uid == uid", "new,group"),
    ("owner-uid", "main", None, "      && request.auth.uid == uid\n", "      && true\n", "new,group"),
    ("owner-not-deleted", "main", None, "      && !isDeletedAccount(uid);", "      && true;", "new,group"),
    ("data-only-field", "main", None, "  return request.resource.data.keys().hasOnly(['hiddenUpTo'])", "  return true", "new,group"),
    ("data-not-future", "main", None, "      && request.resource.data.hiddenUpTo <= request.time;", "      && true;", "new,group"),
    ("unsend-isOfThePair", "main", None, U1, U1.replace("if isOfThePair()", "if true"), "new"),
    ("unsend-areFriends", "main", None, U1, U1.replace("&& areFriends()", "&& true"), "new"),
    ("unsend-own-sender", "main", None, U1, U1.replace("&& resource.data.senderId == request.auth.uid", "&& true"), "new"),
    ("unsend-only-text", "main", "match /chats/{pairId} {", "        && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['text'])", "        && true", "new"),
    ("unsend-empty-text", "main", "match /chats/{pairId} {", "        && request.resource.data.text == '';", "        && true;", "new"),
    ("hide-pair-id", "main", OTO, "        && isPairIdOf(pairId, uid)", "        && true", "new"),
    ("hide-chat-exists", "main", OTO, "        && exists(/databases/$(database)/documents/chats/$(pairId))", "        && true", "new"),
    ("hide-forward-only", "main", OTO, FWD, "      && true;", "new"),
    ("group-hide-two-part", "main", GRP, "        && isTwoPartId(chatId)", "        && true", "group"),
    ("group-hide-exists", "main", GRP, "        && exists(group())", "        && true", "group"),
    ("group-hide-member", "main", GRP, "        && get(group()).data.memberUids.hasAny([uid])", "        && true", "group"),
    ("group-hide-forward-only", "main", GRP, FWD, "      && true;", "group"),
    ("group-unsend-member", "group", None, "  allow update: if isMember()", "  allow update: if true", "group"),
    ("group-unsend-own-sender", "group", None, "      && resource.data.senderId == request.auth.uid", "      && true", "group"),
    ("group-unsend-only-text", "group", None, "      && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['text'])", "      && true", "group"),
    ("group-unsend-empty-text", "group", None, "      && request.resource.data.text == '';", "      && true;", "group"),
]


def indent(text):
    return "".join(("    " + line) if line.strip() else line for line in text.splitlines(True))


def dedent(text):
    return "".join(line[4:] if line.startswith("    ") else line for line in text.splitlines(True))


def main():
    rules = open(RULES).read()
    main_start = rules.index(MAIN_HEADER) + len(MAIN_HEADER)
    main_end = rules.index(MAIN_END) - 1  # the blank line before the association block stays
    group_start = rules.index(GROUP_START)
    group_end = rules.index(GROUP_END, group_start) + len(GROUP_END)
    blocks = {"main": dedent(rules[main_start:main_end]), "group": dedent(rules[group_start:group_end])}
    spans = {"main": (main_start, main_end), "group": (group_start, group_end)}
    lines = []
    for name, which, anchor, old, new, suites in MUTANTS:
        text = blocks[which]
        if anchor:
            i = text.index(anchor)
            head, tail = text[:i], text[i:]
            assert old in tail, name
            mutated = head + tail.replace(old, new, 1)
        else:
            assert text.count(old) == 1, name
            mutated = text.replace(old, new)
        assert mutated != text, name
        start, end = spans[which]
        with open(os.path.join(HERE, name + ".rules"), "w") as out:
            out.write(rules[:start] + indent(mutated) + rules[end:])
        lines.append(f"{name} {suites}\n")
    with open(os.path.join(HERE, "list.txt"), "w") as out:
        out.write("".join(lines))
    print(len(lines), "mutants written")


if __name__ == "__main__":
    main()
