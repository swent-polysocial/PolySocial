// Contributors: Claude (skeleton shared by #30, #31 and #32).
package com.polysocial.model.auth

import com.google.firebase.auth.FirebaseAuth

/**
 * [AuthRepository] backed by Firebase Authentication. Each method is implemented by the task that
 * owns it; until then it throws [NotImplementedError].
 */
class FirebaseAuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) :
    AuthRepository {

  override suspend fun signUp(fullName: String, email: String, password: String): SignUpResult =
      TODO("Implemented in #30")

  override suspend fun logIn(email: String, password: String): LogInResult =
      TODO("Implemented in #32")

  override fun logOut(): Unit = TODO("Implemented in #32")

  override fun currentUser(): AuthUser? =
      auth.currentUser?.let {
        AuthUser(uid = it.uid, email = it.email.orEmpty(), isEmailVerified = it.isEmailVerified)
      }

  override suspend fun sendVerificationEmail(): SendVerificationResult = TODO("Implemented in #31")

  override suspend fun reloadAndCheckVerified(): VerificationCheckResult =
      TODO("Implemented in #31")
}
