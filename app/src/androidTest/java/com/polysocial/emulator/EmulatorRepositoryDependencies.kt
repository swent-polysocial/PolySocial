// Contributors: Claude (Fable 5.1, Anthropic) drafted the re-authentication stubs for #206; Mohamed
// Khellaf (reviewed and revised).
// Contributors: Claude (the signed-in user of the event tests, #118); OpenAI Codex (verification,
// password-reset and Google sign-in contracts, #31, #164, #106). Claude Opus 5.5 (moved here to
// share it with the registration tests, #121). Claude (Opus 5.5, Anthropic) (another app's
// signed-in user, for the group race test, #232).
package com.polysocial.emulator

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.GoogleSignInResult
import com.polysocial.model.auth.LogInResult
import com.polysocial.model.auth.PasswordResetResult
import com.polysocial.model.auth.ReauthResult
import com.polysocial.model.auth.SendVerificationResult
import com.polysocial.model.auth.SignInMethod
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.auth.VerificationResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.AccountType

/**
 * The user [emulator] signed in, for repositories that read [currentUser]. The tests sign in
 * through [FirebaseEmulator.signInVerifiedUser], never through this class. A test with its own
 * Firebase app passes that app's [auth].
 */
class EmulatorSignedInUser(
    emulator: FirebaseEmulator,
    private val auth: FirebaseAuth = emulator.auth,
) : AuthRepository {
  override suspend fun signInWithGoogle(context: Context): GoogleSignInResult =
      throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

  override suspend fun signUp(
      fullName: String,
      email: String,
      password: String,
      accountType: AccountType,
  ): SignUpResult =
      throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

  override suspend fun logIn(email: String, password: String): LogInResult =
      throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

  override suspend fun sendPasswordResetEmail(email: String): PasswordResetResult =
      throw UnsupportedOperationException("Password recovery is outside the emulator fixtures")

  override suspend fun sendVerificationEmail(): SendVerificationResult =
      throw UnsupportedOperationException("Verification is outside the emulator fixtures")

  override suspend fun reloadAndCheckVerified(): VerificationResult =
      throw UnsupportedOperationException("Verification is outside the emulator fixtures")

  override fun currentSignInMethods(): Set<SignInMethod> = emptySet()

  override suspend fun reauthenticateWithPassword(password: String): ReauthResult =
      throw UnsupportedOperationException("Re-authentication is outside the emulator fixtures")

  override suspend fun reauthenticateWithGoogle(context: Context): ReauthResult =
      throw UnsupportedOperationException("Re-authentication is outside the emulator fixtures")

  override fun logOut() = auth.signOut()

  override fun currentUser(): AuthUser? =
      auth.currentUser?.let { AuthUser(it.uid, it.email.orEmpty(), it.isEmailVerified) }
}

/** A device that is always online: the emulator tests reach the emulators on the host. */
object AlwaysOnline : NetworkMonitor {
  override fun isOnline() = true
}
