// Contributors: Claude (logIn, logOut and currentUser for #32).
package com.polysocial.model.auth

import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.tasks.await

/** [AuthRepository] backed by Firebase Authentication. */
class FirebaseAuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) :
    AuthRepository {

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
}
