// Contributors: Claude (Opus 5.5, Anthropic) (the entrance code's token and timing, #468);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.model.checkin

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** How often an organizer's phone replaces the entrance code (#374). */
val ENTRANCE_CODE_ROTATION: Duration = Duration.ofSeconds(60)

/**
 * How long after its rotation the Security Rules accept a code's tokens for a QR check-in (#374): a
 * forwarded screenshot stops working within this time.
 */
val ENTRANCE_CODE_VALIDITY: Duration = Duration.ofMinutes(2)

/**
 * How long before [ENTRANCE_CODE_VALIDITY] runs out the organizer's screen stops showing a code, so
 * a student scanning it at the last second, or a device whose clock is a little off, isn't refused.
 */
val ENTRANCE_CODE_SAFETY_MARGIN: Duration = Duration.ofSeconds(10)

/** The random bytes of a token: 128 bits, too many to guess. */
private const val TOKEN_BYTES = 16

/**
 * An event's entrance code as stored in `events/{eventId}/checkInCode/current` (#468): the [token]
 * the QR code carries, the [previousToken] still accepted while the code changes, and the server's
 * [rotatedAt].
 */
data class EntranceCode(val token: String, val previousToken: String?, val rotatedAt: Instant)

/** Whether this code is [ENTRANCE_CODE_ROTATION] old at [now], so a phone replaces it. */
fun EntranceCode.isDue(now: Instant): Boolean = !now.isBefore(rotatedAt + ENTRANCE_CODE_ROTATION)

/**
 * Whether a student scanning this code at [now] would still be checked in: it is younger than
 * [ENTRANCE_CODE_VALIDITY] minus [ENTRANCE_CODE_SAFETY_MARGIN]. The screen never shows one that
 * isn't.
 */
fun EntranceCode.isScannable(now: Instant): Boolean =
    now.isBefore(rotatedAt + ENTRANCE_CODE_VALIDITY - ENTRANCE_CODE_SAFETY_MARGIN)

/**
 * A new random token: [TOKEN_BYTES] random bytes as 22 base64url characters without padding, so it
 * passes #337's QR ID check and the rules' `[A-Za-z0-9_-]{22}`.
 */
fun newEntranceToken(random: SecureRandom = SecureRandom()): String =
    Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
