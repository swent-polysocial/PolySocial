// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial.model.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.polysocial.utils.runWithMainLooper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TaskAwaitTest {
  @Test
  fun successfulTask_returnsItsResult() = runTest {
    val result = runWithMainLooper { Tasks.forResult("done").await() }

    assertEquals("done", result.getCompleted())
  }

  @Test
  fun failedTask_throwsItsException() = runTest {
    val result = runWithMainLooper {
      Tasks.forException<String>(IllegalStateException("boom")).await()
    }

    // Coroutines may rethrow a copy of the exception (stack-trace recovery), so compare its type
    // and message rather than its identity.
    val error = result.getCompletionExceptionOrNull()
    assertTrue(error is IllegalStateException)
    assertEquals("boom", error?.message)
  }

  @Test
  fun canceledTask_cancelsTheCoroutine() = runTest {
    val result = runWithMainLooper { Tasks.forCanceled<String>().await() }

    assertTrue(result.isCancelled)
  }
}
