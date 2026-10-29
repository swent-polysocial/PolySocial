// Contributors: Claude (Fable 5.1, Anthropic) drafted the auth state source, #198; Mohamed Khellaf
// (reviewed and revised).
package com.polysocial.model.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Reports which account is signed in, so the session guard can protect the offline cache (#198).
 * Kept apart from [AuthRepository] so observing the session doesn't change the screens' contract.
 */
interface AuthSessionSource {
  /**
   * Emits the signed-in UID (`null` when signed out) when collection starts and after every
   * sign-in or sign-out, until collection is cancelled. Conflated: a slow collector gets the latest
   * UID.
   */
  fun uidChanges(): Flow<String?>
}

/** [AuthSessionSource] backed by a Firebase [FirebaseAuth.AuthStateListener]. */
class FirebaseAuthSessionSource(private val auth: () -> FirebaseAuth) : AuthSessionSource {
  constructor() : this({ FirebaseAuth.getInstance() })

  override fun uidChanges(): Flow<String?> =
      callbackFlow {
            val firebase = auth()
            val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
            // Firebase calls the listener on the main thread, once now and after each change.
            firebase.addAuthStateListener(listener)
            awaitClose { firebase.removeAuthStateListener(listener) }
          }
          .conflate()
}
