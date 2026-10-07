// Contributors: Claude (skeleton shared by #30, #31 and #32; logIn and logOut for #32).
package com.polysocial.model.auth

import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.tasks.await

/**
 * [AuthRepository] backed by Firebase Authentication. Each method is implemented by the task that
 * owns it; until then it throws [NotImplementedError].
 */
class FirebaseAuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) :
    AuthRepository {

  override suspend fun signUp(fullName: String, email: String, password: String): SignUpResult =
      TODO("Implemented in #30")

  override suspend fun logIn(email: String, password: String): LogInResult =
      try {
        auth.signInWithEmailAndPassword(email, password).await()
        currentUser()?.let { LogInResult.Success(it) } ?: LogInResult.UnexpectedError
      } catch (e: FirebaseAuthInvalidCredentialsException) {
        LogInResult.WrongCredentials
      } catch (e: FirebaseAuthInvalidUserException) {
        LogInResult.WrongCredentials
      } catch (e: FirebaseNetworkException) {
        LogInResult.NetworkError
      } catch (e: FirebaseException) {
        LogInResult.UnexpectedError
      }

  override fun logOut() = auth.signOut()

  override fun currentUser(): AuthUser? =
      auth.currentUser?.let {
        AuthUser(uid = it.uid, email = it.email.orEmpty(), isEmailVerified = it.isEmailVerified)
      }

  override suspend fun sendVerificationEmail(): SendVerificationResult = TODO("Implemented in #31")

  override suspend fun reloadAndCheckVerified(): VerificationCheckResult =
      TODO("Implemented in #31")
}
