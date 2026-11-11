// Contributors: OpenAI Codex (Google credential service and typed outcomes; removed collision
// outcome for #106);
// Android Developers (Credential Manager button-flow API reference).
// Contributors: Claude (Opus 5.5, Anthropic) reported Google failures disguised as a cancel, #319.
package com.polysocial.model.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.polysocial.R

/** Selected identity retained only in memory while displaying an auth error. */
data class GoogleAccount(val email: String, val displayName: String?)

/** Google sign-in never grants access until Firebase confirms a verified EPFL identity. */
sealed interface GoogleSignInResult {
  data class Success(val user: AuthUser) : GoogleSignInResult

  data object Cancelled : GoogleSignInResult

  data class NotEpfl(val account: GoogleAccount) : GoogleSignInResult

  data object NetworkError : GoogleSignInResult

  data object UnexpectedError : GoogleSignInResult
}

/** Token stays in the data layer; it is neither logged nor persisted. */
class GoogleCredential(val account: GoogleAccount, val idToken: String)

/**
 * Whether this cancellation means the user closed Google's sheet, rather than a failure inside it
 * (#319). On sign-in a dismissal stays silent (#107), in re-authentication it says so; a failure
 * must show an error.
 *
 * Credential Manager also reports some Google failures as a cancellation, with the same Play
 * services status 16 as a dismissal: `[16] Cancelled by user.` is the user, `[16] Account reauth
 * failed.` is a failure, reported with an unregistered SHA-1, an OAuth consent screen set to
 * Internal or no network (android/identity-samples#90). Only Google's wording tells them apart, so
 * a message that says "cancel" (either spelling, any case) is a dismissal, like androidx's
 * `activity is cancelled by the user.`. A missing message counts as one too, since a cancel from
 * Android 14+'s own Credential Manager may carry none. An open PR,
 * capawesome-team/capacitor-firebase#1061, uses a narrower check: the exact phrases "Cancelled by
 * user" and "activity is cancelled by the user", with a missing message as a failure.
 */
internal fun GetCredentialCancellationException.isUserDismissal(): Boolean {
  val text = message
  return text.isNullOrBlank() || text.contains("cancel", ignoreCase = true)
}

/** Acquires a Google credential with the foreground activity, replaceable in offline tests. */
fun interface GoogleCredentialSource {
  suspend fun get(context: Context): GoogleCredential
}

/** Uses the explicit button flow so new users and accounts needing reauthentication can sign in. */
class AndroidGoogleCredentialSource(
    private val manager: (Context) -> CredentialManager = { CredentialManager.create(it) },
) : GoogleCredentialSource {
  override suspend fun get(context: Context): GoogleCredential {
    // Resolve the generated Web client ID only when a picker is requested.
    val clientId = context.getString(R.string.default_web_client_id)
    check(clientId.isNotBlank()) { "Google OAuth configuration missing" }
    val option = GetSignInWithGoogleOption.Builder(clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential =
        try {
          manager(context.applicationContext).getCredential(context, request).credential
        } catch (missing: NoCredentialException) {
          // The explicit button flow already includes new accounts; there is no authorized-only
          // filter to retry. Report a recoverable failure (e.g. the device has no usable account).
          throw IllegalStateException("Google credential unavailable", missing)
        }
    check(
        credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    )
    val google = GoogleIdTokenCredential.createFrom(credential.data)
    return GoogleCredential(
        GoogleAccount(google.email.orEmpty(), google.displayName),
        google.idToken,
    )
  }
}
