// Contributors: Claude (Opus 5.5, Anthropic) (the username format and reserved names, #448).
package com.polysocial.model.user

import java.util.Locale

/** The fewest characters a username may have (#366, decided by the Sprint 6 PO in #445). */
const val MIN_USERNAME_LENGTH = 3

/** The most characters a username may have (#366, decided by the Sprint 6 PO in #445). */
const val MAX_USERNAME_LENGTH = 20

/**
 * Names nobody may claim, so no student can pass for the app, the school or its staff (Sprint 6
 * PO, #445). The `usernames/{name}` Security Rule refuses the same list.
 */
val RESERVED_USERNAMES: Set<String> =
    setOf(
        "polysocial",
        "admin",
        "support",
        "epfl",
        "agepoly",
        "staff",
        "moderator",
        "official",
        "help",
    )

/**
 * A lowercase letter, then lowercase letters, digits, `_` or `.`, [MIN_USERNAME_LENGTH] to
 * [MAX_USERNAME_LENGTH] characters in all: the `usernames/{name}` Security Rule's pattern.
 */
private val USERNAME_PATTERN = Regex("[a-z][a-z0-9_.]{2,19}")

/** Why a name can't be a username, before asking the server whether it is taken. */
enum class UsernameError {
  /** It doesn't start with a letter, has another character, or is too short or too long. */
  FORMAT,

  /** It is in [RESERVED_USERNAMES]. */
  RESERVED,
}

/**
 * The username [input] names, as typed in Edit profile (#451) or in Friends' "Add by @username"
 * (#452): without the spaces around it and one leading `@`, in lowercase. Check the result with
 * [usernameError].
 */
fun normalizeUsername(input: String): String = input.trim().removePrefix("@").lowercase(Locale.ROOT)

/** Why [name] can't be a username, or null if it can. [name] is checked as is, not normalized. */
fun usernameError(name: String): UsernameError? =
    when {
      !USERNAME_PATTERN.matches(name) -> UsernameError.FORMAT
      name in RESERVED_USERNAMES -> UsernameError.RESERVED
      else -> null
    }
