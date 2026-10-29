// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests, #198; Mohamed Khellaf (reviewed and
// revised).
package com.polysocial.model.firebase

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthSessionSource
import com.polysocial.model.auth.FakeAuthSessionSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The account transitions of #198, with an in-memory owner store and auth source, and a real
 * [FirestoreSession] over mocked Firestore instances: each instance the session opens is recorded,
 * and its persistence removal counts as one cache clear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSessionGuardTest {
  private val owner = FakeSessionOwnerStore()
  private val auth = FakeAuthSessionSource()

  /** The next `terminate()` and `clearPersistence()` tasks of every instance. */
  private var termination: () -> Task<Void> = { Tasks.forResult(null) }
  private var persistence: () -> Task<Void> = { Tasks.forResult(null) }

  /** The instances the session opened, oldest first. */
  private val instances = mutableListOf<FirebaseFirestore>()
  private var terminations = 0
  private var clears = 0

  private val session = newSession()
  private val guard = FirestoreSessionGuard(auth, owner, session)

  private fun newSession() = FirestoreSession {
    mockk<FirebaseFirestore>().also { instance ->
      every { instance.terminate() } answers
          {
            terminations++
            termination()
          }
      every { instance.clearPersistence() } answers
          {
            clears++
            persistence()
          }
      instances += instance
    }
  }

  /** A scope that runs each auth change at once, like the app's `Dispatchers.Main.immediate`. */
  private fun TestScope.immediateScope() =
      CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

  private fun TestScope.startGuard() = guard.start(immediateScope())

  /** [session] is accessible and gives the instance opened last, never a cleared one. */
  private fun assertOpenedLast(session: FirestoreSession) {
    val database = session.database
    assertSame(instances.last(), database)
  }

  private fun assertAccessBlocked() {
    try {
      session.database
      fail("the previous account's cache must not be readable before it is cleared")
    } catch (_: IllegalStateException) {
      // What every repository maps to its typed error (SessionRepositoryFailureTest).
    }
  }

  @Test
  fun freshInstallSignedOut_clearsNothing() = runTest {
    startGuard()
    runCurrent()

    assertEquals(SessionGuardResult.Ready, guard.ensureSession(null))
    assertEquals(0, clears)
    assertTrue(instances.isEmpty())
    assertNull(owner.uid)
    assertFalse(session.isBlocked)
  }

  @Test
  fun firstSignInOfAProcess_clearsThenRecordsTheAccount() = runTest {
    // Signed out when the process started: its disk cache may still hold an earlier account.
    startGuard()
    auth.uid = "student-b"
    runCurrent()

    assertEquals(1, clears)
    assertEquals("student-b", owner.uid)
    assertFalse(session.isBlocked)
    // A fresh instance, not the one that was cleared.
    val fresh = session.database
    assertEquals(2, instances.size)
    assertSame(instances[1], fresh)
    assertEquals(SessionGuardResult.Ready, guard.ensureSession("student-b"))
    assertEquals(1, clears)
  }

  @Test
  fun switchingAccounts_blocksAtOnceThenClearsBeforeTheNewAccountReads() = runTest {
    owner.uid = "student-a"
    auth.uid = "student-a"
    val previous = session.database
    val stopped = TaskCompletionSource<Void>()
    termination = { stopped.task }
    startGuard()

    auth.uid = "student-b"

    // Blocked within the auth callback itself, before the clear has finished.
    assertTrue(session.isBlocked)
    assertAccessBlocked()
    val start = async { guard.ensureSession("student-b") }
    runCurrent()
    assertFalse("app start must wait for the clear", start.isCompleted)
    assertEquals("student-a", owner.uid)

    stopped.setResult(null)
    runCurrent()

    assertEquals(SessionGuardResult.Ready, start.await())
    assertEquals(1, clears)
    assertEquals("student-b", owner.uid)
    val fresh = session.database
    assertTrue("the cleared instance must not be reused", fresh !== previous)
  }

  @Test
  fun sameAccountAfterRecreation_keepsTheCache() = runTest {
    owner.uid = "student-a"
    auth.uid = "student-a"
    session.database
    // A recreated activity or a restarted process: a new guard over the same stored owner.
    val restartedSession = newSession()
    val restarted = FirestoreSessionGuard(auth, owner, restartedSession)

    assertEquals(SessionGuardResult.Ready, restarted.ensureSession("student-a"))
    restarted.start(immediateScope())
    runCurrent()

    assertEquals(0, terminations)
    assertEquals(0, clears)
    assertOpenedLast(restartedSession)
    assertEquals(0, owner.writes)
  }

  @Test
  fun explicitLogout_isNotClearedASecondTime() = runTest {
    owner.uid = "student-a"
    auth.uid = "student-a"
    session.database
    startGuard()

    // Profile logout clears first, then signs out.
    assertEquals(CacheClearResult.Cleared, session.clear())
    auth.uid = null
    runCurrent()

    assertEquals(1, clears)
    assertNull(owner.uid)
    assertFalse(session.isBlocked)
    // Nothing was read since, so the next account doesn't need another clear either.
    auth.uid = "student-b"
    runCurrent()
    assertEquals(1, clears)
    assertEquals("student-b", owner.uid)
    assertOpenedLast(session)
  }

  @Test
  fun unexpectedSessionLoss_clearsTheCacheLeftBehind() = runTest {
    owner.uid = "student-a"
    auth.uid = "student-a"
    startGuard()
    // Student A read through the app, so the cache may hold A's documents.
    session.database

    // For example a disabled account or a changed password: signed out without logout's clear.
    auth.uid = null
    runCurrent()

    assertEquals(1, clears)
    assertNull(owner.uid)
    assertFalse(session.isBlocked)
  }

  @Test
  fun failedClear_keepsAccessBlockedAndTheOwner_untilARetrySucceeds() = runTest {
    owner.uid = "student-a"
    persistence = { Tasks.forException(FirebaseException("fictional failure")) }

    assertEquals(SessionGuardResult.CleanupFailed, guard.ensureSession("student-b"))

    assertTrue(session.isBlocked)
    assertAccessBlocked()
    assertEquals("student-a", owner.uid)

    persistence = { Tasks.forResult(null) }
    assertEquals(SessionGuardResult.Ready, guard.ensureSession("student-b"))

    assertEquals("student-b", owner.uid)
    assertFalse(session.isBlocked)
    assertOpenedLast(session)
    // The retry reused the stopped instance instead of opening the old cache again.
    assertEquals(1, terminations)
    assertEquals(2, clears)
  }

  @Test
  fun sameAccountAfterAFailedClear_clearsAgainBeforeReading() = runTest {
    // Logout's clear failed and the student is still signed in (or A → null failed and A is back).
    owner.uid = "student-a"
    session.database
    persistence = { Tasks.forException(FirebaseException("fictional failure")) }
    assertEquals(CacheClearResult.UnexpectedError, session.clear())
    assertTrue(session.isBlocked)

    persistence = { Tasks.forResult(null) }
    assertEquals(SessionGuardResult.Ready, guard.ensureSession("student-a"))

    assertEquals(2, clears)
    assertFalse(session.isBlocked)
    assertOpenedLast(session)
    assertEquals("student-a", owner.uid)
  }

  @Test
  fun stalledClear_timesOutAsAFailureAndCanBeRetried() = runTest {
    owner.uid = "student-a"
    termination = { TaskCompletionSource<Void>().task }

    val start = async { guard.ensureSession("student-b") }
    advanceUntilIdle()

    assertEquals(SessionGuardResult.CleanupFailed, start.await())
    assertAccessBlocked()
    assertEquals("student-a", owner.uid)

    termination = { Tasks.forResult(null) }
    assertEquals(SessionGuardResult.Ready, guard.ensureSession("student-b"))
    assertEquals("student-b", owner.uid)
  }

  @Test
  fun cancelledCaller_waitsForTheStartedClearThenCancels() = runTest {
    owner.uid = "student-a"
    val removed = TaskCompletionSource<Void>()
    persistence = { removed.task }
    val start = async { guard.ensureSession("student-b") }
    runCurrent()

    start.cancel()
    runCurrent()
    assertFalse("a started clear finishes first", start.isCompleted)
    assertAccessBlocked()

    removed.setResult(null)
    runCurrent()

    assertTrue(start.isCancelled)
    // The cache is clean but the change was not recorded, so the next check records it.
    assertEquals("student-a", owner.uid)
    assertFalse(session.isBlocked)
    assertEquals(SessionGuardResult.Ready, guard.ensureSession("student-b"))
    assertEquals(1, clears)
    assertEquals("student-b", owner.uid)
  }

  @Test
  fun concurrentChecks_shareOneClear() = runTest {
    owner.uid = "student-a"
    val removed = TaskCompletionSource<Void>()
    persistence = { removed.task }

    // The auth listener and app start see the same change at the same time.
    val listener = async { guard.ensureSession("student-b") }
    val start = async { guard.ensureSession("student-b") }
    runCurrent()
    assertEquals(1, clears)
    assertFalse(start.isCompleted)

    removed.setResult(null)

    assertEquals(SessionGuardResult.Ready, listener.await())
    assertEquals(SessionGuardResult.Ready, start.await())
    assertEquals(1, clears)
    assertEquals(1, owner.writes)
  }

  @Test
  fun unavailableAuth_endsObservationWithoutCrashing() = runTest {
    // What FirebaseAuthSessionSource does without a Firebase app: it ends without emitting.
    val noAuth =
        object : AuthSessionSource {
          override fun uidChanges() = emptyFlow<String?>()
        }

    val observing = FirestoreSessionGuard(noAuth, owner, session).start(immediateScope())
    runCurrent()

    assertTrue(observing.isCompleted)
    assertFalse(observing.isCancelled)
    assertEquals(0, clears)
  }

  @Test
  fun unexpectedFailureDuringACheck_blocksTheCacheAndKeepsObserving() = runTest {
    owner.uid = "student-a"
    auth.uid = "student-a"
    session.database
    val observing = startGuard()

    // The stored owner can't be read when student B signs in.
    owner.failure = IllegalStateException("fictional storage failure")
    auth.uid = "student-b"
    runCurrent()

    assertFalse("a failed check must not end observation", observing.isCompleted)
    assertTrue(session.isBlocked)
    assertAccessBlocked()
    assertEquals(0, clears)

    // Observation goes on: the next change is checked and cleared as usual.
    owner.failure = null
    auth.uid = "student-c"
    runCurrent()
    assertEquals(1, clears)
    assertEquals("student-c", owner.uid)
    assertFalse(session.isBlocked)

    // App start's own check (its Retry path) then finds the cache ready.
    assertEquals(SessionGuardResult.Ready, guard.ensureSession("student-c"))
    assertOpenedLast(session)
  }

  @Test
  fun unexpectedFailureDuringACheck_isRecoveredByAppStartsRetry() = runTest {
    owner.uid = "student-a"
    auth.uid = "student-a"
    session.database
    startGuard()
    owner.failure = IllegalStateException("fictional storage failure")
    auth.uid = "student-b"
    runCurrent()
    assertAccessBlocked()

    owner.failure = null
    assertEquals(SessionGuardResult.Ready, guard.ensureSession("student-b"))

    assertEquals(1, clears)
    assertEquals("student-b", owner.uid)
    assertFalse(session.isBlocked)
    assertOpenedLast(session)
  }

  @Test
  fun cancellingTheScope_stopsObserving() = runTest {
    val observing = startGuard()
    observing.cancel()
    runCurrent()

    auth.uid = "student-b"
    runCurrent()

    assertTrue(observing.isCancelled)
    assertEquals(0, clears)
    assertNull(owner.uid)
  }
}
