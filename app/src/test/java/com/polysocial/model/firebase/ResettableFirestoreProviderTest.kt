// Contributors: Claude (wrote these tests, including the cancelled-caller clear, #156).
package com.polysocial.model.firebase

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResettableFirestoreProviderTest {
  private val first = instance()
  private val second = instance()
  private var created = 0
  private val provider = ResettableFirestoreProvider {
    created++
    if (created == 1) first else second
  }

  /** An instance whose termination and cache clearing succeed unless a test says otherwise. */
  private fun instance(): FirebaseFirestore = mockk {
    every { terminate() } returns Tasks.forResult(null)
    every { clearPersistence() } returns Tasks.forResult(null)
  }

  private fun failed(): Task<Void> =
      Tasks.forException(
          FirebaseFirestoreException("failed", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
      )

  @Test
  fun theInstance_isCreatedOnFirstUseOnly() = runTest {
    assertEquals(0, created)

    assertSame(first, provider.get())
    assertSame(first, provider.get())
    assertEquals(1, created)
  }

  @Test
  fun clearCache_terminatesTheInstanceThenClearsItsCache() = runTest {
    provider.get()

    assertEquals(ClearCacheResult.Cleared, provider.clearCache())

    verifyOrder {
      first.terminate()
      first.clearPersistence()
    }
  }

  @Test
  fun afterClearingTheCache_readsGetAFreshInstance() = runTest {
    provider.get()

    provider.clearCache()

    assertSame(second, provider.get())
    assertSame(second, provider.get())
  }

  @Test
  fun clearCache_beforeAnyRead_stillClearsTheCacheOfAnEarlierSession() = runTest {
    assertEquals(ClearCacheResult.Cleared, provider.clearCache())

    verify { first.clearPersistence() }
    assertSame(second, provider.get())
  }

  @Test
  fun aFailedTermination_isReportedAndTheNextReadStillWorks() = runTest {
    every { first.terminate() } returns failed()
    provider.get()

    assertEquals(ClearCacheResult.Failed, provider.clearCache())

    verify(exactly = 0) { first.clearPersistence() }
    assertSame(second, provider.get())
  }

  @Test
  fun aFailedCacheClear_isReportedAndTheNextReadStillWorks() = runTest {
    every { first.clearPersistence() } returns failed()
    provider.get()

    assertEquals(ClearCacheResult.Failed, provider.clearCache())

    assertSame(second, provider.get())
  }

  @Test
  fun anUnexpectedException_isReportedAsAFailure() = runTest {
    every { first.terminate() } throws IllegalStateException("unexpected")
    provider.get()

    assertEquals(ClearCacheResult.Failed, provider.clearCache())
    assertSame(second, provider.get())
  }

  @Test
  fun aCancelledTask_isReportedAsAFailure() = runTest {
    every { first.terminate() } returns Tasks.forCanceled()
    provider.get()

    assertEquals(ClearCacheResult.Failed, provider.clearCache())
  }

  @Test
  fun cancellingTheCallerMidTermination_stillClearsTheCacheThenCancelsTheCaller() = runTest {
    val termination = TaskCompletionSource<Void>()
    every { first.terminate() } returns termination.task
    provider.get()
    var result: ClearCacheResult? = null
    val clearing = launch(start = CoroutineStart.UNDISPATCHED) { result = provider.clearCache() }

    clearing.cancel()
    termination.setResult(null)
    clearing.join()

    verify(exactly = 1) { first.clearPersistence() }
    assertTrue(clearing.isCancelled)
    assertEquals(null, result)
    assertSame(second, provider.get())
  }

  @Test
  fun aReadDuringTheClear_waitsForTheCacheToBeClearedAndGetsTheFreshInstance() = runTest {
    val termination = TaskCompletionSource<Void>()
    every { first.terminate() } returns termination.task
    provider.get()
    val clearing = launch(start = CoroutineStart.UNDISPATCHED) { provider.clearCache() }

    val read = async(start = CoroutineStart.UNDISPATCHED) { provider.get() }
    assertFalse(read.isCompleted)
    assertEquals(1, created)

    termination.setResult(null)

    assertSame(second, read.await())
    clearing.join()
    verify { first.clearPersistence() }
  }

  @Test
  fun awaitRetired_returnsOnceTheCacheIsCleared() = runTest {
    val db = provider.get()
    val waiting = async(start = CoroutineStart.UNDISPATCHED) { provider.awaitRetired(db) }
    assertFalse(waiting.isCompleted)

    provider.clearCache()
    runCurrent()

    assertTrue(waiting.isCompleted)
  }

  @Test
  fun awaitRetired_returnsAtOnceForAnInstanceAlreadyReplaced() = runTest {
    val old = provider.get()
    provider.clearCache()
    provider.get()

    val waiting = async(start = CoroutineStart.UNDISPATCHED) { provider.awaitRetired(old) }
    assertTrue(waiting.isCompleted)
  }
}
