// Contributors: OpenAI Codex (persisted per-account resend deadlines, injectable time and KTX
// preference editing for #31).
// Contributors: Claude (Opus 5.5, Anthropic) added the address-keyed deadline, #225; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.model.auth

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local timing metadata; no email address, credentials or verification code is persisted. */
data class VerificationTiming(val sentAtMillis: Long = 0, val retryAtMillis: Long = 0)

/**
 * Persists resend timing across process death, independently for each account and for each email
 * address.
 *
 * The account's timing ([read], [write], [clear]) tells whether this account was already sent a
 * link. The address's timing ([readAddress], [writeAddress], [clearAddress]) outlives the account,
 * so deleting an unverified account and signing up again with the same address keeps the cooldown
 * (#99, #225). Addresses are matched trimmed and case-insensitively.
 */
interface VerificationStore {
  suspend fun read(uid: String): VerificationTiming

  suspend fun write(uid: String, timing: VerificationTiming)

  suspend fun clear(uid: String)

  suspend fun readAddress(email: String): VerificationTiming

  suspend fun writeAddress(email: String, timing: VerificationTiming)

  suspend fun clearAddress(email: String)
}

/** Wall-clock time allows a persisted deadline to survive process and device restarts. */
fun interface VerificationClock {
  fun nowMillis(): Long
}

/**
 * Private local storage; disk operations run off the main thread. An address is stored only as the
 * SHA-256 digest of its normalised form, never as text.
 */
class PreferencesVerificationStore @Inject constructor(@ApplicationContext context: Context) :
    VerificationStore {
  private val preferences =
      context.getSharedPreferences("verification_timing", Context.MODE_PRIVATE)

  override suspend fun read(uid: String): VerificationTiming = readKey(uid)

  override suspend fun write(uid: String, timing: VerificationTiming) = writeKey(uid, timing)

  override suspend fun clear(uid: String) = clearKey(uid)

  override suspend fun readAddress(email: String): VerificationTiming = readKey(addressKey(email))

  override suspend fun writeAddress(email: String, timing: VerificationTiming) =
      writeKey(addressKey(email), timing)

  override suspend fun clearAddress(email: String) = clearKey(addressKey(email))

  private suspend fun readKey(key: String): VerificationTiming =
      withContext(Dispatchers.IO) {
        VerificationTiming(
            preferences.getLong("$key.sent", 0),
            preferences.getLong("$key.retry", 0),
        )
      }

  private suspend fun writeKey(key: String, timing: VerificationTiming): Unit =
      withContext(Dispatchers.IO) {
        preferences.edit(commit = true) {
          putLong("$key.sent", timing.sentAtMillis)
          putLong("$key.retry", timing.retryAtMillis)
        }
      }

  private suspend fun clearKey(key: String): Unit =
      withContext(Dispatchers.IO) {
        preferences.edit(commit = true) {
          remove("$key.sent")
          remove("$key.retry")
        }
      }

  /** Firebase UIDs have no colon, so an address's key never matches an account's. */
  private fun addressKey(email: String): String =
      "address:" +
          MessageDigest.getInstance("SHA-256")
              .digest(email.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
              .joinToString("") { "%02x".format(it) }
}
