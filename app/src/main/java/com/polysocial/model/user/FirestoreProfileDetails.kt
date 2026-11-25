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

internal fun DocumentSnapshot.readProfileVisibility(): ProfileVisibility {
  val stored = getString(PROFILE_VISIBILITY_FIELD) ?: return ProfileVisibility.PRIVATE
  return ProfileVisibility.entries.first { it.value == stored }
}

internal fun UserProfile.requireValidBio() {
  require(bio == null || bio.length in 1..MAX_PROFILE_BIO_LENGTH)
}
