// Contributors: Claude (coroutine bridge for Firebase Tasks).
package com.polysocial.model.auth

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspends until this Firebase [Task] completes, returning its result or throwing its exception.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
  addOnCompleteListener { task ->
    val error = task.exception
    when {
      error != null -> cont.resumeWithException(error)
      task.isCanceled -> cont.cancel()
      else -> cont.resume(task.result)
    }
  }
}
