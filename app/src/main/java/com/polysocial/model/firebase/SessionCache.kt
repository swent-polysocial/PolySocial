// Contributors: Claude (Fable 5.1, Anthropic) drafted the clean-cache tracking and guard blocking,
// #198; Mohamed Khellaf (reviewed and revised).
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
   * True once a [clear] succeeded in this process and no instance was opened since, so the
   * persisted cache is known to be empty. A fresh process starts `false`: its disk cache may hold
   * the previous account's documents even though no instance is open yet.
   */
  private var empty = false

  /** True while access is blocked: cleanup was requested and has not succeeded yet. */
  val isBlocked: Boolean
    get() = synchronized(stateLock) { cleanupRequired }

  /**
   * Blocks [database] until the next successful [clear], unless the persisted cache is known to be
   * empty (cleared in this process and not reopened since). Runs synchronously, so a session guard
   * can block access the moment it detects another account, before its suspending [clear] starts.
   * Returns whether a [clear] is needed.
   */
  fun blockUnlessEmpty(): Boolean =
      synchronized(stateLock) {
        if (!empty) cleanupRequired = true
        !empty
      }

  /**
   * Resolve per repository operation. During cleanup or after failed persistence removal, fail
   * through the repository's typed error path instead of returning a terminated instance or opening
   * the uncleared cache. All holder state is protected across calling threads.
   */
  val database: FirebaseFirestore
    get() =
        synchronized(stateLock) {
          check(!cleanupRequired) { "Firestore cache cleanup must finish before access" }
          current
              ?: provider().also {
                current = it
                empty = false
              }
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
                  empty = false
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
              empty = true
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
