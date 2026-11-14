# Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372; never merged).
# PROTOTYPE for #372 (never merged): writes one mutant of the rules per condition of the sketch,
# each with one condition removed, so the row tests can show which row catches it. The mutants go
# next to this script, from the rules one folder up: python3 firebase/firestore/mutants/make.py
import os
out = os.path.dirname(os.path.abspath(__file__))
base = open(os.path.join(out, '..', 'firestore.rules')).read()

def mutant(name, old, new, count=1):
    assert base.count(old) == count, (name, base.count(old))
    open(os.path.join(out, name + '.rules'), 'w').write(base.replace(old, new))

# ---- the event's create and update
mutant('e01-create-any-number-type', "    && request.resource.data.get('attendeeCount', 0) is int\n    && request.resource.data.get('attendeeCount', 0) == 0;", "    && request.resource.data.get('attendeeCount', 0) == 0;")
mutant('e02-create-any-count', "    && request.resource.data.get('attendeeCount', 0) is int\n    && request.resource.data.get('attendeeCount', 0) == 0;", "    && request.resource.data.get('attendeeCount', 0) is int;")
mutant('e03-organizer-changes-count', ".hasAny(['createdBy', 'isAssociationEvent', 'attendeeCount'])", ".hasAny(['createdBy', 'isAssociationEvent'])")
mutant('e04-organizer-any-number-type', "\n        && request.resource.data.get('attendeeCount', 0) is int)", ")")
mutant('e05-register-any-number-type', "  return after is int\n      && after == before + 1", "  return after == before + 1")
mutant('e06-register-any-step', "  return after is int\n      && after == before + 1", "  return after is int")
mutant('e07-register-no-capacity', "      && (!(capacity is number) || after <= capacity)\n", "")
mutant('e08-register-when-registered', "      && !exists(ownRegistration())\n      && existsAfter(ownRegistration());", "      && existsAfter(ownRegistration());")
mutant('e09-register-without-registration', "      && !exists(ownRegistration())\n      && existsAfter(ownRegistration());", "      && !exists(ownRegistration());")
mutant('e10-cancel-any-number-type', "  return after is int\n      && after >= 0", "  return after >= 0")
mutant('e11-cancel-below-zero', "      && after >= 0\n", "")
mutant('e12-cancel-any-step', "      && after == before - 1\n", "")
mutant('e13-cancel-without-registration', "      && exists(ownRegistration())\n      && !existsAfter(ownRegistration());", "      && !existsAfter(ownRegistration());")
mutant('e14-cancel-keeping-registration', "      && exists(ownRegistration())\n      && !existsAfter(ownRegistration());", "      && exists(ownRegistration());")
mutant('e15-branch-not-only-students', "  return isStudent()\n      && studentCanReadThisEvent()", "  return studentCanReadThisEvent()")
mutant('e16-branch-unreadable-event', "  return isStudent()\n      && studentCanReadThisEvent()", "  return isStudent()")
mutant('e17-branch-other-fields', "      && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['attendeeCount'])\n", "")
# ---- the registration's create and delete
mutant('a01-create-uncounted', "\n    && countAfter() == countBefore() + 1;", ";")
mutant('a02-delete-uncounted', """allow delete: if isSelf()
    && (resource == null
        || !canReadEvent()
        || countBefore() == 0
        || countAfter() == countBefore() - 1);""", "allow delete: if isSelf();")
mutant('a03-delete-missing-needs-count', "    && (resource == null\n        || !canReadEvent()", "    && (!canReadEvent()")
mutant('a04-delete-unreadable-needs-count', "        || !canReadEvent()\n        || countBefore() == 0", "        || countBefore() == 0")
mutant('a05-delete-at-zero-needs-count', "        || countBefore() == 0\n", "")
mutant('a06-create-for-any-student', "allow create: if isSelf()\n    && !isDeletedAccount(uid)", "allow create: if isStudent()\n    && !isDeletedAccount(uid)")
mutant('a07-create-any-fields', "    && request.resource.data.keys().hasOnly(['uid', 'registeredAt'])\n    && request.resource.data.uid == uid", "    && request.resource.data.uid == uid")
mutant('a08-create-any-uid', "    && request.resource.data.uid == uid\n    && request.resource.data.registeredAt == request.time\n    && countAfter()", "    && request.resource.data.registeredAt == request.time\n    && countAfter()")
mutant('a09-create-any-time', "    && request.resource.data.registeredAt == request.time\n    && countAfter()", "    && countAfter()")
mutant('a10-create-deleted-account', "allow create: if isSelf()\n    && !isDeletedAccount(uid)\n    && canReadEvent()\n    && hasNotEnded()", "allow create: if isSelf()\n    && canReadEvent()\n    && hasNotEnded()")
mutant('a11-create-ended-event', "    && canReadEvent()\n    && hasNotEnded()\n    && request.resource.data.keys()", "    && canReadEvent()\n    && request.resource.data.keys()")
mutant('a12-create-unreadable-event', "    && !isDeletedAccount(uid)\n    && canReadEvent()\n    && hasNotEnded()\n    && request.resource.data.keys()", "    && !isDeletedAccount(uid)\n    && hasNotEnded()\n    && request.resource.data.keys()")
# ---- the reviewer's two (the create rule without its own checks)
mutant('r1-create-only-counted', """allow create: if isSelf()
    && !isDeletedAccount(uid)
    && canReadEvent()
    && hasNotEnded()
    && request.resource.data.keys().hasOnly(['uid', 'registeredAt'])
    && request.resource.data.uid == uid
    && request.resource.data.registeredAt == request.time
""", "allow create: if isStudent()\n")
mutant('r2-create-no-own-checks', """allow create: if isSelf()
    && !isDeletedAccount(uid)
    && canReadEvent()
    && hasNotEnded()
    && request.resource.data.keys().hasOnly(['uid', 'registeredAt'])
    && request.resource.data.uid == uid
    && request.resource.data.registeredAt == request.time
""", """allow create: if isStudent()
    && !isDeletedAccount(uid)
    && canReadEvent()
    && hasNotEnded()
""")
# ---- a boundary (second review): the delete rule's counter at 0 widened to 1, so the only counted
# registration could be deleted alone and leave the counter at 1 (row X3)
mutant('b1-delete-alone-at-one', "        || countBefore() == 0\n", "        || countBefore() <= 1\n")
# ---- conditions that both documents' rules check: removing one alone changes nothing, so each
# pair is also removed together.
def double(name, *pairs):
    text = base
    for old, new in pairs:
        assert text.count(old) == 1, (name, text.count(old))
        text = text.replace(old, new)
    open(os.path.join(out, name + '.rules'), 'w').write(text)

double('d1-register-any-step-uncounted',
       ("  return after is int\n      && after == before + 1", "  return after is int"),
       ("\n    && countAfter() == countBefore() + 1;", ";"))
double('d2-register-unreadable-event',
       ("  return isStudent()\n      && studentCanReadThisEvent()", "  return isStudent()"),
       ("    && !isDeletedAccount(uid)\n    && canReadEvent()\n    && hasNotEnded()\n    && request.resource.data.keys()", "    && !isDeletedAccount(uid)\n    && hasNotEnded()\n    && request.resource.data.keys()"))
double('d3-not-only-students',
       ("  return isStudent()\n      && studentCanReadThisEvent()", "  return studentCanReadThisEvent()"),
       ("""function isSelf() {
          return isStudent() && request.auth.uid == uid;
        }

        function canReadEvent() {
          return exists(event())""", """function isSelf() {
          return request.auth.uid == uid;
        }

        function canReadEvent() {
          return exists(event())"""))
print(len([f for f in os.listdir(out) if f.endswith('.rules')]), 'mutants')
