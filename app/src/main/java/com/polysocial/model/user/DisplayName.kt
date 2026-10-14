// Contributors: Claude (the full name's limit, #143, written test-first).
package com.polysocial.model.user

/**
 * The most characters a full name may have (#143). Sign-up and the profile step check it, and the
 * `users/{uid}` Security Rule enforces it on `displayName`.
 */
const val MAX_DISPLAY_NAME_LENGTH = 50

/**
 * Whether [name] is over [MAX_DISPLAY_NAME_LENGTH]. The name is saved trimmed, so spaces around it
 * don't count, and characters are counted like the Security Rule counts them: an emoji or an
 * accented letter is one character, not its UTF-16 units.
 */
fun isDisplayNameTooLong(name: String): Boolean {
  val trimmed = name.trim()
  return trimmed.codePointCount(0, trimmed.length) > MAX_DISPLAY_NAME_LENGTH
}
