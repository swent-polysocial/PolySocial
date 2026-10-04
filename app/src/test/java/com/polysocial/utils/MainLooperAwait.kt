// Contributors: Claude Opus 5.5 (test helper for code that awaits Firebase Tasks).
package com.polysocial.utils

import android.os.Looper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.robolectric.Shadows.shadowOf

/**
 * Runs [block] until it suspends on a Firebase Task, then delivers the Task's completion listeners
 * (which Play Services posts to the main looper) and lets [block] finish. Returns the finished
 * [Deferred], so a test can read its result, its exception or its cancellation. [block] runs in a
 * [SupervisorJob] so an expected failure does not fail the whole test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.runWithMainLooper(block: suspend () -> T): Deferred<T> {
  val deferred = async(SupervisorJob()) { block() }
  runCurrent()
  shadowOf(Looper.getMainLooper()).idle()
  runCurrent()
  return deferred
}
