// Contributors: Claude (Fable 5.1, Anthropic) drafted the cache owner store, #198; Mohamed Khellaf
// (reviewed and revised).
package com.polysocial.model.firebase

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Remembers which account the offline cache was filled for, across process restarts. Only the
 * pseudonymous Firebase UID is stored, never an email or a name.
 */
interface SessionOwnerStore {
  /** The stored UID, `null` when signed out or never set (a fresh install has no cache). */
  fun read(): String?

  fun write(uid: String?)
}

/**
 * [SessionOwnerStore] in private preferences. Reads are synchronous so a change can be detected in
 * the auth callback itself. Writes are applied asynchronously: a write lost to a process kill
 * leaves the previous UID, which only causes one more clear on the next start.
 */
class PreferencesSessionOwnerStore @Inject constructor(@ApplicationContext context: Context) :
    SessionOwnerStore {
  private val preferences = context.getSharedPreferences("session_guard", Context.MODE_PRIVATE)

  override fun read(): String? =
      try {
        preferences.getString(KEY, null)
      } catch (_: ClassCastException) {
        // An unreadable value can't prove who owns the cache; match no account so it is cleared.
        UNKNOWN_OWNER
      }

  override fun write(uid: String?) = preferences.edit { putString(KEY, uid) }

  private companion object {
    const val KEY = "uid"
    /** Firebase UIDs are never empty, and `null` means signed out. */
    const val UNKNOWN_OWNER = ""
  }
}
