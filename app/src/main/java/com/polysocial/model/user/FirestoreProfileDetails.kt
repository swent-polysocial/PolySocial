// Contributors: OpenAI Codex (GPT-6.2 Sol, high; profile visibility and bio for #446 under
// Lucas Pinto Ricardo’s supervision and approval).
package com.polysocial.model.user

import com.google.firebase.firestore.DocumentSnapshot

internal const val PROFILE_VISIBILITY_FIELD = "profileVisibility"
internal const val BIO_FIELD = "bio"

/** Omit the private default and absent bio in both profile documents. */
internal fun UserProfile.toProfileDetailsDocument(): Map<String, Any> = buildMap {
  if (profileVisibility == ProfileVisibility.PUBLIC) {
    put(PROFILE_VISIBILITY_FIELD, profileVisibility.value)
  }
  bio?.let { put(BIO_FIELD, it) }
}

/** Keep unknown, missing, or malformed stored visibility private. */
internal fun DocumentSnapshot.readProfileVisibility(): ProfileVisibility {
  val stored = get(PROFILE_VISIBILITY_FIELD)
  return ProfileVisibility.entries.firstOrNull { it.value == stored } ?: ProfileVisibility.PRIVATE
}

internal fun DocumentSnapshot.readBio(): String? = get(BIO_FIELD) as? String

/** An invalid caller-supplied bio becomes `UnexpectedError` through the repository. */
internal fun UserProfile.requireValidBio() {
  require(bio == null || bio.length in 1..MAX_PROFILE_BIO_LENGTH)
}
