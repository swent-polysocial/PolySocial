// Contributors: OpenAI Codex (logout cache reset boundary and cancellation/thread safety, #156).
package com.polysocial.model.firebase

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class CacheClearResult {
  Cleared,
  UnexpectedError,
}

/** Cache cleanup must finish before another account may enter the app. */
interface SessionCache {
  /**
   * Terminates the database and clears its persisted data before allowing a fresh instance. Returns
   * [CacheClearResult.UnexpectedError] if database creation or cleanup fails, including SDK
   * cancellation or the 10-second cleanup timeout. Once cleanup starts, database access stays
   * blocked until a retry succeeds. Caller cancellation waits for started cleanup to finish or time
   * out, then propagates to the caller.
   */
  suspend fun clear(): CacheClearResult
}

/** Owns the replaceable Firestore instance; persistence remains enabled between logouts. */
@Singleton
class FirestoreSession(private val provider: () -> FirebaseFirestore) : SessionCache {
  @Inject constructor() : this({ FirebaseFirestore.getInstance() })

  private val stateLock = Any()
  private val cleanupLock = Mutex()
  private var cleanupRequired = false
  private var terminated = false
  private var current: FirebaseFirestore? = null

  /**
   * Resolve per repository operation. During cleanup or after failed persistence removal, fail
   * through the repository's typed error path instead of returning a terminated instance or opening
   * the uncleared cache. All holder state is protected across calling threads.
   */
  val database: FirebaseFirestore
    get() =
        synchronized(stateLock) {
          check(!cleanupRequired) { "Firestore cache cleanup must finish before access" }
          current ?: provider().also { current = it }
        }

  override suspend fun clear(): CacheClearResult = cleanupLock.withLock {
    currentCoroutineContext().ensureActive()
    // Once started, finish the bounded cleanup even if the screen's scope is cancelled.
    val result =
        withContext(NonCancellable) {
          try {
            val previous =
                synchronized(stateLock) {
                  val instance = current ?: provider().also { current = it }
                  cleanupRequired = true
                  instance
                }
            withTimeout(10_000) {
              if (!synchronized(stateLock) { terminated }) {
                previous.terminate().await()
                synchronized(stateLock) { terminated = true }
              }
              previous.clearPersistence().await()
            }
            synchronized(stateLock) {
              current = null
              terminated = false
              cleanupRequired = false
            }
            CacheClearResult.Cleared
          } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            CacheClearResult.UnexpectedError
          } catch (_: FirebaseException) {
            CacheClearResult.UnexpectedError
          } catch (_: Exception) {
            CacheClearResult.UnexpectedError
          }
        }
    currentCoroutineContext().ensureActive()
    result
  }
}
