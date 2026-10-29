// Contributors: Claude (Fable 5.1, Anthropic) drafted the session guard, #198; Mohamed Khellaf
// (reviewed and revised).
// Contributors: Claude (Opus 5.5, Anthropic) started it from the application, made failed checks
// fail safe and tested it, #198; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.firebase

import com.polysocial.model.auth.AuthSessionSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of [SessionGuard.ensureSession]. */
enum class SessionGuardResult {
  /** The local cache holds nothing from another account; reads may start. */
  Ready,

  /** The previous account's cache could not be cleared; access stays blocked until a retry. */
  CleanupFailed,
}

/**
 * Keeps one account from reading another account's offline Firestore cache (#198). Security Rules
 * cannot protect reads served only from the local cache, so the cache must be cleared whenever the
 * signed-in UID differs from the one it was filled for.
 */
interface SessionGuard {
  /**
   * Makes the cache safe for [uid] (`null` when signed out) and suspends until it is. Await it
   * before the first read for an account. Returns [SessionGuardResult.CleanupFailed] if the clear
   * fails or times out; database access then stays blocked, and calling it again retries the clear.
   * The caller's cancellation propagates once a started clear has finished.
   */
  suspend fun ensureSession(uid: String?): SessionGuardResult
}

/**
 * [SessionGuard] that remembers in [owner] the UID the cache belongs to, and clears [session] when
 * the signed-in UID differs (null → B, A → B, A → null). The same UID across activity recreation or
 * process restart keeps the cache, so same-account offline use is preserved. A fresh install has no
 * stored UID and no cache, so a first start while signed out never clears.
 *
 * **No double clear on logout.** Explicit logout (and any flow that clears before signing out)
 * calls [SessionCache.clear], then signs out. The resulting A → null change finds the cache already
 * empty ([FirestoreSession.blockUnlessEmpty] returns false: cleared in this process and not
 * reopened since), so the guard only records the new UID. Firestore access between that clear and
 * the sign-out reopens the cache, and the guard then clears again: safe, only slower.
 *
 * **When access is blocked.** On a change, [FirestoreSession.blockUnlessEmpty] runs synchronously,
 * before the suspending clear, so every later `FirestoreSession.database` access fails
 * (repositories map it to their typed errors) until a clear succeeds. The new UID is stored only
 * after that success, so a failed or interrupted clear is retried by the next [ensureSession] call
 * (app start's Retry) or on the next launch. App start awaits [ensureSession] before its profile
 * read, the first read of every sign-in, so that path is covered whatever order Firebase reports
 * the change in. Changes while the app shell is shown are caught through [AuthSessionSource]. A
 * read that resolved the database before the change was detected is not stopped, but the clear
 * terminates the instance it uses.
 */
@Singleton
class FirestoreSessionGuard
@Inject
constructor(
    private val auth: AuthSessionSource,
    private val owner: SessionOwnerStore,
    private val session: FirestoreSession,
) : SessionGuard {
  private val reconciling = Mutex()

  /**
   * Observes sign-ins and sign-outs for the life of [scope]. `PolySocialApp` calls it once, with an
   * application-wide scope on `Dispatchers.Main.immediate`, so it runs for the whole process and
   * detection blocks access within Firebase's main-thread callback, before the clear runs. If
   * Firebase Authentication cannot be created, [AuthSessionSource.uidChanges] ends at once (nobody
   * can sign in or out in this process) and observation ends with it; app start still checks before
   * every first read.
   *
   * **An unexpected failure while checking a change fails safe.** It must neither crash the app
   * (the scope has no handler) nor let the new account read a cache that may hold the previous
   * one's documents. So the cache is blocked unless it is known to be empty (in which case there is
   * nothing to protect), the owner is left unchanged, and observation goes on with the next change.
   * The user gets out through app start: its "couldn't load profile" Retry calls [ensureSession]
   * again, which clears the blocked cache and records the account.
   */
  fun start(scope: CoroutineScope): Job =
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        auth.uidChanges().collect { uid ->
          try {
            ensureSession(uid)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // Fail safe instead of crashing: no account reads until a later check clears.
            session.blockUnlessEmpty()
          }
        }
      }

  override suspend fun ensureSession(uid: String?): SessionGuardResult {
    if (owner.read() != uid) session.blockUnlessEmpty()
    return reconciling.withLock {
      // A concurrent check may have finished this change already, or left its clear failed.
      if (owner.read() == uid && !session.isBlocked) return@withLock SessionGuardResult.Ready
      if (session.blockUnlessEmpty() && session.clear() != CacheClearResult.Cleared)
          return@withLock SessionGuardResult.CleanupFailed
      owner.write(uid)
      SessionGuardResult.Ready
    }
  }
}
