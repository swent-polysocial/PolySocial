// Contributors: OpenAI Codex (cache reset ordering, retry, replacement and cancellation/thread
// safety proof, #156).
package com.polysocial.model.firebase

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSessionTest {
  private val old = mockk<FirebaseFirestore>()
  private val fresh = mockk<FirebaseFirestore>()
  private var reads = 0
  private val session = FirestoreSession { if (reads++ == 0) old else fresh }

  @Test
  fun clearsOnlyAfterTerminationThenReplacesTheInstance() = runTest {
    val termination = TaskCompletionSource<Void>()
    every { old.terminate() } returns termination.task
    every { old.clearPersistence() } returns Tasks.forResult(null)
    assertSame(old, session.database)
    val clear = async { session.clear() }
    runCurrent()
    verify(exactly = 0) { old.clearPersistence() }
    assertFalse(clear.isCompleted)
    termination.setResult(null)
    assertEquals(CacheClearResult.Cleared, clear.await())
    assertSame(fresh, session.database)
    assertSame(fresh, session.database)
  }

  @Test
  fun failedCleanupBlocksAccessAndRetriesOnlyCleanup() = runTest {
    every { old.terminate() } returns Tasks.forResult(null)
    every { old.clearPersistence() } returns Tasks.forException(FirebaseException("test failure"))
    assertEquals(CacheClearResult.UnexpectedError, session.clear())
    assertAccessBlocked()
    every { old.clearPersistence() } returns Tasks.forResult(null)
    assertEquals(CacheClearResult.Cleared, session.clear())
    assertSame(fresh, session.database)
    verify(exactly = 1) { old.terminate() }
  }

  @Test
  fun terminationFailureDoesNotClearOrReplace() = runTest {
    every { old.terminate() } returns Tasks.forException(IllegalStateException("test failure"))
    assertEquals(CacheClearResult.UnexpectedError, session.clear())
    assertAccessBlocked()
    verify(exactly = 0) { old.clearPersistence() }
  }

  @Test
  fun stalledCleanupTimesOutAndCanRetry() = runTest {
    every { old.terminate() } returns TaskCompletionSource<Void>().task
    val clear = async { session.clear() }
    advanceUntilIdle()
    assertEquals(CacheClearResult.UnexpectedError, clear.await())
    verify(exactly = 0) { old.clearPersistence() }
  }

  @Test
  fun callerCancellationFinishesStartedCleanupBeforePropagating() = runTest {
    val persistence = TaskCompletionSource<Void>()
    every { old.terminate() } returns Tasks.forResult(null)
    every { old.clearPersistence() } returns persistence.task
    val clear = async { session.clear() }
    runCurrent()
    clear.cancel()
    runCurrent()
    assertFalse(clear.isCompleted)
    assertAccessBlocked()
    persistence.setResult(null)
    runCurrent()
    assertTrue(clear.isCancelled)
    assertSame(fresh, session.database)
    verify(exactly = 1) { old.clearPersistence() }
  }

  @Test
  fun cancelledCallerWithCleanupTimeoutBlocksAccessUntilRetry() = runTest {
    every { old.terminate() } returns Tasks.forResult(null)
    every { old.clearPersistence() } returns TaskCompletionSource<Void>().task
    val clear = async { session.clear() }
    runCurrent()
    clear.cancel()
    advanceUntilIdle()
    assertTrue(clear.isCancelled)
    assertAccessBlocked()
    every { old.clearPersistence() } returns Tasks.forResult(null)
    assertEquals(CacheClearResult.Cleared, session.clear())
    assertSame(fresh, session.database)
    verify(exactly = 1) { old.terminate() }
  }

  @Test
  fun readsFromAnotherThreadCannotOpenAnInstanceDuringCleanup() = runTest {
    val persistence = TaskCompletionSource<Void>()
    every { old.terminate() } returns Tasks.forResult(null)
    every { old.clearPersistence() } returns persistence.task
    val clear = async { session.clear() }
    runCurrent()
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
      executor.submit { assertAccessBlocked() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
    assertEquals(1, reads)
    persistence.setResult(null)
    assertEquals(CacheClearResult.Cleared, clear.await())
    assertSame(fresh, session.database)
  }

  @Test
  fun concurrentCleanupCallsAreSerialized() = runTest {
    val persistence = TaskCompletionSource<Void>()
    every { old.terminate() } returns Tasks.forResult(null)
    every { old.clearPersistence() } returns persistence.task
    every { fresh.terminate() } returns Tasks.forResult(null)
    every { fresh.clearPersistence() } returns Tasks.forResult(null)
    val first = async { session.clear() }
    runCurrent()
    val second = async { session.clear() }
    runCurrent()
    verify(exactly = 0) { fresh.terminate() }
    persistence.setResult(null)
    assertEquals(CacheClearResult.Cleared, first.await())
    assertEquals(CacheClearResult.Cleared, second.await())
    verify(exactly = 1) {
      old.terminate()
      fresh.terminate()
    }
  }

  private fun assertAccessBlocked() {
    try {
      session.database
      fail("access must wait for successful cleanup")
    } catch (_: IllegalStateException) {
      // The repository maps this to its typed error; no stopped instance escapes.
    }
  }

  @Test
  fun cancelledSdkTaskReturnsFailureWhileCallerRemainsActive() = runTest {
    val task =
        TaskCompletionSource<Void>(
            com.google.android.gms.tasks.CancellationTokenSource().apply { cancel() }.token
        )
    every { old.terminate() } returns task.task
    assertEquals(CacheClearResult.UnexpectedError, session.clear())
    assertTrue(currentCoroutineContext().isActive)
    verify(exactly = 0) { old.clearPersistence() }
  }
}
