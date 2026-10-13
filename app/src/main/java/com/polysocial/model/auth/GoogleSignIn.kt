// Contributors: OpenAI Codex (Google credential service and typed outcomes for #106);
// Android Developers (Credential Manager button-flow API reference).
package com.polysocial.model.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
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

  data class OtherSignInMethod(val account: GoogleAccount) : GoogleSignInResult

  data object NetworkError : GoogleSignInResult

  data object UnexpectedError : GoogleSignInResult
}

/** Token stays in the data layer; it is neither logged nor persisted. */
class GoogleCredential(val account: GoogleAccount, val idToken: String)

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
    val credential = manager(context.applicationContext).getCredential(context, request).credential
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
