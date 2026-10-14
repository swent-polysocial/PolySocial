// Contributors: Claude (logIn, logOut and currentUser for #32); OpenAI Codex
// (GPT-6.1 Sol, medium; sign-up and display-name integration; verification delivery and
// token refresh for #31).
package com.polysocial.model.auth

import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * [AuthRepository] backed by Firebase Authentication. FirebaseAuth is resolved on the first
 * operation so idle Compose rendering and Robolectric screen tests need no initialized Firebase
 * app.
 */
class FirebaseAuthRepository private constructor(authProvider: () -> FirebaseAuth) :
    AuthRepository {
  // Idle screen rendering needs no SDK access; resolve Firebase only for an operation.
  private val auth by lazy(authProvider)

  constructor(auth: FirebaseAuth) : this({ auth })

  constructor() : this({ FirebaseAuth.getInstance() })

  override suspend fun signUp(
      fullName: String,
      email: String,
      password: String,
  ): SignUpResult {
    if (!isEpflEmail(email)) return SignUpResult.InvalidDomain
    return try {
      val user =
          auth.createUserWithEmailAndPassword(email.trim(), password).await().user
              ?: return SignUpResult.UnexpectedError
      try {
        user
            .updateProfile(
                UserProfileChangeRequest.Builder().setDisplayName(fullName.trim()).build()
            )
            .await()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // The account exists: do not offer a retry that would create a duplicate account.
        return SignUpResult.DisplayNameError
      }
      SignUpResult.Success(user.snapshot())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: FirebaseAuthUserCollisionException) {
      SignUpResult.AlreadyInUse
    } catch (_: FirebaseNetworkException) {
      SignUpResult.NetworkError
    } catch (_: FirebaseAuthWeakPasswordException) {
      SignUpResult.InvalidPassword
    } catch (_: FirebaseTooManyRequestsException) {
      SignUpResult.TooManyRequests
    } catch (_: Exception) {
      SignUpResult.UnexpectedError
    }
  }

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

  override suspend fun sendVerificationEmail(): SendVerificationResult {
    val user = auth.currentUser ?: return SendVerificationResult.NotSignedIn
    return try {
      user.sendEmailVerification().await()
      SendVerificationResult.Sent
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: FirebaseTooManyRequestsException) {
      SendVerificationResult.Throttled
    } catch (_: FirebaseNetworkException) {
      SendVerificationResult.NetworkError
    } catch (_: Exception) {
      SendVerificationResult.UnexpectedError
    }
  }

  override suspend fun reloadAndCheckVerified(): VerificationResult {
    val user = auth.currentUser ?: return VerificationResult.NotSignedIn
    return try {
      user.reload().await()
      if (auth.currentUser?.uid != user.uid) return VerificationResult.NotSignedIn
      if (!user.isEmailVerified) return VerificationResult.Unverified
      user.getIdToken(true).await()
      if (auth.currentUser?.uid != user.uid) VerificationResult.NotSignedIn
      else VerificationResult.Verified
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: FirebaseNetworkException) {
      VerificationResult.NetworkError
    } catch (_: Exception) {
      VerificationResult.UnexpectedError
    }
  }

  override fun logOut() = auth.signOut()

  override fun currentUser(): AuthUser? = auth.currentUser?.snapshot()

  private fun FirebaseUser.snapshot() =
      AuthUser(
          uid = uid,
          email = email.orEmpty(),
          isEmailVerified = isEmailVerified,
          displayName = displayName,
      )
}
