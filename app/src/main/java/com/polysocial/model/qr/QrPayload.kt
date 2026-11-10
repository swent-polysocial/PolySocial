// Contributors: Claude Opus 5.5 (drafted the QR payload format and parser, #337); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.model.qr

/**
 * What a scanned PolySocial QR code asks for. The app writes only these two forms:
 * - `polysocial://friend/{uid}`: a student's own code, shown on their profile;
 * - `polysocial://checkin/{eventId}/{token}`: an event's entrance code.
 *
 * Anything else, including a web link, another app's code or a damaged scan, is [Invalid], so the
 * screens show one "not a PolySocial code" message and nothing unchecked reaches Firestore.
 */
sealed interface QrPayload {
  data class Friend(val uid: String) : QrPayload

  data class CheckIn(val eventId: String, val token: String) : QrPayload

  data object Invalid : QrPayload
}

/** The longest ID or token a code may hold; Firebase UIDs and Firestore IDs are far shorter. */
const val MAX_QR_ID_LENGTH = 128

private const val SCHEME = "polysocial://"
private const val FRIEND = "friend"
private const val CHECKIN = "checkin"

/**
 * One ID or token: letters, digits, `-` and `_`. This covers Firebase UIDs, Firestore auto-IDs and
 * the `demo-` IDs, and rules out `/`, `.`/`..`, spaces and percent-encoding.
 */
private val QR_ID = Regex("[A-Za-z0-9_-]{1,$MAX_QR_ID_LENGTH}")

/**
 * Reads a scanned code. Only surrounding whitespace is ignored (scanners can add a newline); the
 * scheme and host must be exactly lowercase, and a query, fragment, trailing slash or extra segment
 * makes the code [QrPayload.Invalid].
 */
fun parseQrPayload(raw: String): QrPayload {
  val text = raw.trim()
  if (!text.startsWith(SCHEME)) return QrPayload.Invalid
  val segments = text.removePrefix(SCHEME).split('/')
  val ids = segments.drop(1)
  if (!ids.all(QR_ID::matches)) return QrPayload.Invalid
  return when {
    segments[0] == FRIEND && ids.size == 1 -> QrPayload.Friend(ids[0])
    segments[0] == CHECKIN && ids.size == 2 -> QrPayload.CheckIn(ids[0], ids[1])
    else -> QrPayload.Invalid
  }
}

/**
 * The code a student shows on their profile, or `null` for a UID [parseQrPayload] would refuse, so
 * the screen can show an error instead of a code nobody can scan.
 */
fun friendQrPayload(uid: String): String? = if (QR_ID.matches(uid)) "$SCHEME$FRIEND/$uid" else null
