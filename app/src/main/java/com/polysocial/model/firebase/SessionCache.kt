// Contributors: OpenAI Codex (logout cache reset boundary, #109).
package com.polysocial.model.firebase

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

enum class CacheClearResult {
  Cleared,
  UnexpectedError,
}

/** Cache cleanup must finish before another account may enter the app. */
interface SessionCache {
  suspend fun clear(): CacheClearResult
}

/** Owns the replaceable Firestore instance; persistence remains enabled between logouts. */
@Singleton
class FirestoreSession(private val provider: () -> FirebaseFirestore) : SessionCache {
  @Inject constructor() : this({ FirebaseFirestore.getInstance() })

  private var terminated = false
  private var current: FirebaseFirestore? = null

  val database: FirebaseFirestore
    get() = current ?: provider().also { current = it }

  override suspend fun clear(): CacheClearResult =
      try {
        val previous = database
        withTimeout(10_000) {
          if (!terminated) {
            previous.terminate().await()
            terminated = true
          }
          previous.clearPersistence().await()
        }
        current = null
        terminated = false
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
