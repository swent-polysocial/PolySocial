// Contributors: Claude (the signed-in user of the event tests, #118); OpenAI Codex (verification,
// password-reset and Google sign-in contracts, #31, #164, #106). Claude Opus 5.5 (moved here to
// share it with the registration tests, #121).
package com.polysocial.emulator

import android.content.Context
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.GoogleSignInResult
import com.polysocial.model.auth.LogInResult
import com.polysocial.model.auth.PasswordResetResult
import com.polysocial.model.auth.SendVerificationResult
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.auth.VerificationResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.AccountType

/**
 * The user [emulator] signed in, for repositories that read [currentUser]. The tests sign in
 * through [FirebaseEmulator.signInVerifiedUser], never through this class.
 */
class EmulatorSignedInUser(private val emulator: FirebaseEmulator) : AuthRepository {
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

  override fun logOut() = emulator.auth.signOut()

  override fun currentUser(): AuthUser? =
      emulator.auth.currentUser?.let { AuthUser(it.uid, it.email.orEmpty(), it.isEmailVerified) }
}

/** A device that is always online: the emulator tests reach the emulators on the host. */
object AlwaysOnline : NetworkMonitor {
  override fun isOnline() = true
}
