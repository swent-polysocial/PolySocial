// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
package com.polysocial.model.firebase

import android.content.Context
import android.content.SharedPreferences
import com.polysocial.model.user.PublicProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records which friend queries the server completed. Firestore owns the rows, but its cache can
 * return an empty or partial query after eviction. Only an exact match can supply an offline count.
 * The cache stores fingerprints only and is cleared with the Firestore session.
 */
@Singleton
class ProfileFriendsCache(private val preferences: SharedPreferences) {
  @Inject
  constructor(
      @ApplicationContext context: Context
  ) : this(context.getSharedPreferences("profile_friends_cache", Context.MODE_PRIVATE))

  internal fun remember(viewer: String, profile: PublicProfile, ids: List<String>) {
    preferences.edit().putString(key(viewer, profile.uid), fingerprint(profile, ids)).apply()
  }

  internal fun matches(viewer: String, profile: PublicProfile, ids: List<String>): Boolean =
      preferences.getString(key(viewer, profile.uid), null) == fingerprint(profile, ids)

  internal fun forget(viewer: String, owner: String) {
    preferences.edit().remove(key(viewer, owner)).apply()
  }

  internal fun clear() {
    check(preferences.edit().clear().commit()) { "Could not clear profile friends cache" }
  }

  private fun key(viewer: String, owner: String): String = digest(listOf(viewer, owner))

  private fun fingerprint(profile: PublicProfile, ids: List<String>): String =
      digest(listOf(profile.profileVisibility.value) + ids.sorted())

  private fun digest(values: List<String>): String {
    val encoded = values.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
  }
}
