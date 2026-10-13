// Contributors: Claude (drafted the resettable Firestore provider and its cache clearing, #156).
package com.polysocial.model.firebase

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Outcome of [FirestoreProvider.clearCache]. */
sealed interface ClearCacheResult {
  data object Cleared : ClearCacheResult

  /** The cache may still hold the previous account's data. The app keeps working regardless. */
  data object Failed : ClearCacheResult
}

/**
 * Gives every Firestore repository the current [FirebaseFirestore]. Logging out replaces the
 * instance to clear the offline cache (#156), so repositories ask for it on every operation instead
 * of keeping one.
 */
interface FirestoreProvider {
  /**
   * The instance to use for one operation. Never keep it: [clearCache] terminates it, after which
   * the next call returns a fresh one. Waits while [clearCache] runs.
   */
  suspend fun get(): FirebaseFirestore

  /**
   * Returns once [db] is no longer the current instance (right away if it already isn't), so a
   * snapshot listener on [db] can end: a terminated instance stops sending events without an error.
   */
  suspend fun awaitRetired(db: FirebaseFirestore)

  /**
   * Terminates the current instance and deletes Firestore's offline cache (cached documents and
   * pending writes), so the next account on this phone starts empty. The next [get] returns a fresh
   * instance, even if clearing failed.
   */
  suspend fun clearCache(): ClearCacheResult
}

/**
 * [FirestoreProvider] that creates its instance with [create] on first use, and again after each
 * [clearCache]. [create] must return a new instance once the previous one is terminated, as
 * [FirebaseFirestore.getInstance] does.
 *
 * [get] and [clearCache] share a lock, so no operation starts on a fresh instance before the cache
 * is cleared: the old and new instances use the same cache files, and the SDK refuses to clear them
 * while an instance is running. For the same reason there is no timeout on clearing.
 */
class ResettableFirestoreProvider(private val create: () -> FirebaseFirestore) : FirestoreProvider {
  private val lock = Mutex()
  private val current = MutableStateFlow<FirebaseFirestore?>(null)

  override suspend fun get(): FirebaseFirestore = lock.withLock {
    current.value ?: create().also { current.value = it }
  }

  override suspend fun awaitRetired(db: FirebaseFirestore) {
    current.first { it !== db }
  }

  override suspend fun clearCache(): ClearCacheResult = lock.withLock {
    try {
      // A cache written in an earlier app session is cleared even if nothing used it since.
      val db = current.value ?: create()
      current.value = null
      db.terminate().await()
      db.clearPersistence().await()
      ClearCacheResult.Cleared
    } catch (e: FirebaseException) {
      ClearCacheResult.Failed
    } catch (e: CancellationException) {
      // A cancelled caller stops here; a cancelled Firebase task is a failure.
      currentCoroutineContext().ensureActive()
      ClearCacheResult.Failed
    } catch (e: Exception) {
      ClearCacheResult.Failed
    }
  }
}
