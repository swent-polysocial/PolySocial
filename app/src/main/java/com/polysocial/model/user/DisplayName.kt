// Contributors: Claude (the full name's limit, #143, written test-first); Claude (Opus 5.5,
// Anthropic) (a public profile's first name, moved from the Chats list for Find a group, #497).
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

/**
 * The first word of a public profile's name ("Léa Martin" → "Léa"), how the app names a student
 * briefly: the Chats list's members and senders (#364) and Find a group's friend (#497). Null when
 * the name is blank, so the caller shows something else.
 */
fun PublicProfile.firstName(): String? =
    displayName.trim().split(' ').firstOrNull()?.takeIf { it.isNotBlank() }
