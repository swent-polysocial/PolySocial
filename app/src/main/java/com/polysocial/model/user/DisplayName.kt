// Contributors: Claude (the full name's limit, #143, written test-first).
package com.polysocial.model.user

/**
 * The most characters a full name may have (#143). Sign-up and the profile step check it, and the
 * `users/{uid}` Security Rule enforces it on `displayName`.
 */
const val MAX_DISPLAY_NAME_LENGTH = 50

/**
 * Whether [name] is over [MAX_DISPLAY_NAME_LENGTH]. The name is saved trimmed, so spaces around it
 * don't count. Characters are counted in UTF-16 units, as the Security Rule's `size()` counts them
 * (checked on the Firestore emulator): an accented letter is one, an emoji is two.
 */
fun isDisplayNameTooLong(name: String): Boolean = name.trim().length > MAX_DISPLAY_NAME_LENGTH
