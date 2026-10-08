// Contributors: OpenAI Codex (persisted per-account resend deadlines and injectable time for #31).
package com.polysocial.model.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local timing metadata; no email address, credentials or verification code is persisted. */
data class VerificationTiming(val sentAtMillis: Long = 0, val retryAtMillis: Long = 0)

/** Persists resend timing across process death, independently for each account. */
interface VerificationStore {
  suspend fun read(uid: String): VerificationTiming

  suspend fun write(uid: String, timing: VerificationTiming)

  suspend fun clear(uid: String)
}

/** Wall-clock time allows a persisted deadline to survive process and device restarts. */
fun interface VerificationClock {
  fun nowMillis(): Long
}

/** Private local storage; disk operations run off the main thread. */
class PreferencesVerificationStore @Inject constructor(@ApplicationContext context: Context) :
    VerificationStore {
  private val preferences =
      context.getSharedPreferences("verification_timing", Context.MODE_PRIVATE)

  override suspend fun read(uid: String): VerificationTiming =
      withContext(Dispatchers.IO) {
        VerificationTiming(
            preferences.getLong("$uid.sent", 0),
            preferences.getLong("$uid.retry", 0),
        )
      }

  override suspend fun write(uid: String, timing: VerificationTiming): Unit =
      withContext(Dispatchers.IO) {
        check(
            preferences
                .edit()
                .putLong("$uid.sent", timing.sentAtMillis)
                .putLong("$uid.retry", timing.retryAtMillis)
                .commit()
        )
      }

  override suspend fun clear(uid: String): Unit =
      withContext(Dispatchers.IO) {
        check(preferences.edit().remove("$uid.sent").remove("$uid.retry").commit())
      }
}
