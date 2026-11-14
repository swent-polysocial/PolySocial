// Contributors: Claude (Fable 5.2 and Opus 5.5, Anthropic) (prototype of the attendeeCount design, #372; never merged).
// PROTOTYPE for #372 (never merged): #373's `register` and `cancel`, modelled with the JS SDK.
// The transactions are write-only (no read of the event, FieldValue.increment), run with the SDK's
// default attempts, and a refused write is classified afterwards by one read-only transaction tried
// once, as the Architecture Diagram describes under decision 21. `steps` records what the model
// did, for the tests.
const {
  doc,
  increment,
  runTransaction,
  serverTimestamp,
} = require("firebase/firestore");

/** The codes that mean the server couldn't be reached: NetworkError, nothing else is tried. */
const NETWORK = new Set(["unavailable", "deadline-exceeded"]);

/**
 * Registers [uid] for events/[eventId]: one write-only transaction creates the registration and
 * raises the event's attendeeCount with increment(1). The rules accept it only for an event the
 * student can read, that hasn't ended and has a place left. If it is refused, classifyRefusal
 * says why.
 * Returns "Registered", "Full", "NotFound", "NetworkError" or "UnexpectedError".
 */
async function register(db, uid, eventId, steps = [], options = undefined) {
  const event = doc(db, `events/${eventId}`);
  const registration = doc(db, `events/${eventId}/attendees/${uid}`);
  try {
    await runTransaction(db, async (transaction) => {
      transaction.set(registration, { uid, registeredAt: serverTimestamp() });
      transaction.update(event, { attendeeCount: increment(1) });
    }, options);
    steps.push("write");
    return "Registered";
  } catch (error) {
    steps.push(`write: ${error.code}`);
    if (NETWORK.has(error.code)) return "NetworkError";
  }
  return classifyRefusal(db, event, registration, steps);
}

/**
 * Says why a registration was refused, with one read-only transaction tried once: today's
 * `register` transaction without its write. It reads the student's registration (already
 * registered is a success), then the event. A transaction's reads are requests to the server, not
 * a listener like getDocFromServer (#194), so it fails with "unavailable" instead of waiting, and
 * a read denied after the registration's is the event's. Its commit only verifies the documents
 * it read: a change to the event in between fails it, which is UnexpectedError.
 */
async function classifyRefusal(db, event, registration, steps) {
  let stage = "read registration";
  try {
    return await runTransaction(db, async (transaction) => {
      // 1. Already registered (a second tap, another device, or a first answer that was lost).
      const own = await transaction.get(registration);
      steps.push(`read registration: ${own.exists() ? "exists" : "missing"}`);
      if (own.exists()) {
        stage = "commit";
        return "Registered";
      }
      // 2. The event: gone or unreadable (the read rule denies both), then full.
      stage = "read event";
      const stored = await transaction.get(event);
      stage = "commit";
      if (!stored.exists()) {
        steps.push("read event: missing");
        return "NotFound";
      }
      // Like the app's Event.isFull: capacity is read as a Number and truncated to an Int, which
      // agrees with the rule (count + 1 <= capacity) for a decimal capacity too.
      const { capacity, attendeeCount = 0 } = stored.data();
      if (typeof capacity === "number" && attendeeCount >= Math.trunc(capacity)) {
        steps.push("read event: full");
        return "Full";
      }
      // 3. Anything else the rules refuse: an ended event, a deleted account, rules not deployed.
      steps.push("read event: a place left");
      return "UnexpectedError";
    }, { maxAttempts: 1 });
  } catch (error) {
    steps.push(`${stage}: ${error.code}`);
    if (stage === "read event" && error.code === "permission-denied") return "NotFound";
    return NETWORK.has(error.code) ? "NetworkError" : "UnexpectedError";
  }
}

/**
 * Cancels [uid]'s registration for events/[eventId]: one write-only transaction deletes it and
 * lowers the event's attendeeCount with increment(-1). If that is refused, a second transaction
 * deletes the registration alone, which the rules accept only when there is nothing to count (no
 * registration, an event that is gone or unreadable, a counter at 0 or absent). No read is needed.
 * Returns "Cancelled", "NetworkError" or "UnexpectedError".
 */
async function cancel(db, uid, eventId, steps = [], options = undefined) {
  const event = doc(db, `events/${eventId}`);
  const registration = doc(db, `events/${eventId}/attendees/${uid}`);
  try {
    await runTransaction(db, async (transaction) => {
      transaction.delete(registration);
      transaction.update(event, { attendeeCount: increment(-1) });
    }, options);
    steps.push("write");
    return "Cancelled";
  } catch (error) {
    steps.push(`write: ${error.code}`);
    if (NETWORK.has(error.code)) return "NetworkError";
  }
  try {
    await runTransaction(db, async (transaction) => {
      transaction.delete(registration);
    }, options);
    steps.push("delete registration only");
    return "Cancelled";
  } catch (error) {
    steps.push(`delete registration only: ${error.code}`);
    return NETWORK.has(error.code) ? "NetworkError" : "UnexpectedError";
  }
}

module.exports = { cancel, register };
