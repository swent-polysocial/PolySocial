// Contributors: OpenAI Codex (durable association signup intent and privacy-safe keys, #125).
package com.polysocial.model.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remembers association signup before creating an Auth account, so process recreation cannot send
 * its verified owner to student setup. This local hint grants no backend permissions. Callers clear
 * it only once the private profile durably records the association account type.
 */
interface AssociationSignupStore {
  suspend fun remember(email: String)

  suspend fun contains(email: String): Boolean

  suspend fun clear(email: String)
}

/** Default for manually constructed controllers that do not exercise association signup. */
object EmptyAssociationSignupStore : AssociationSignupStore {
  override suspend fun remember(email: String) = Unit

  override suspend fun contains(email: String) = false

  override suspend fun clear(email: String) = Unit
}

/**
 * Private device preferences contain only mailbox digests and boolean intent markers, never raw
 * addresses or credentials. Synchronous commits run off the main thread and fail before signup if
 * the durable marker cannot be saved. This does not recover intent on a different device.
 */
class PreferencesAssociationSignupStore @Inject constructor(@ApplicationContext context: Context) :
    AssociationSignupStore {
  private val preferences = context.getSharedPreferences("association_signup", Context.MODE_PRIVATE)

  override suspend fun remember(email: String): Unit =
      withContext(Dispatchers.IO) {
        if (!preferences.edit().putBoolean(key(email), true).commit()) {
          throw IOException("Could not save association signup intent")
        }
      }

  override suspend fun contains(email: String): Boolean =
      withContext(Dispatchers.IO) { preferences.getBoolean(key(email), false) }

  override suspend fun clear(email: String): Unit =
      withContext(Dispatchers.IO) {
        if (!preferences.edit().remove(key(email)).commit()) {
          throw IOException("Could not clear association signup intent")
        }
      }

  private fun key(email: String): String =
      MessageDigest.getInstance("SHA-256")
          .digest(email.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }
}
