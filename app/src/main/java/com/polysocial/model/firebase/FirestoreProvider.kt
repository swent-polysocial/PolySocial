// Contributors: Claude (drafted the resettable Firestore provider and its cache clearing, #156).
package com.polysocial.model.firebase

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gives every Firestore repository the current [FirebaseFirestore]. Logging out will replace the
 * instance to clear the offline cache (#156), so repositories ask for it on every operation instead
 * of keeping one.
 */
interface FirestoreProvider {
  /** The instance to use for one operation. Never keep it: it can be replaced. */
  suspend fun get(): FirebaseFirestore
}

/** [FirestoreProvider] that creates its instance with [create] on first use. */
class ResettableFirestoreProvider(private val create: () -> FirebaseFirestore) : FirestoreProvider {
  private val lock = Mutex()
  private var current: FirebaseFirestore? = null

  override suspend fun get(): FirebaseFirestore = lock.withLock {
    current ?: create().also { current = it }
  }
}
