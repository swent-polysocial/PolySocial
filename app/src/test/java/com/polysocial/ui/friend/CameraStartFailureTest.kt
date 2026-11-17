// Contributors: Claude Opus 5.5 (wrote these tests, #359 review); Konstantinos Perdikeas
// (directed, reviewed and owns them).
package com.polysocial.ui.friend

import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The camera's start-up failures, forced by hand: a real phone can't be made to fail on cue, so
 * these drive [bindOrReport] and [reportFailure] with a bind that throws and a failed future.
 */
class CameraStartFailureTest {
  private var errors = 0
  private val onError: () -> Unit = { errors++ }

  /** A future and the completer that settles it. */
  private fun future():
      Pair<
          ListenableFuture<Void>,
          CallbackToFutureAdapter.Completer<Void>,
      > {
    lateinit var completer: CallbackToFutureAdapter.Completer<Void>
    val future = CallbackToFutureAdapter.getFuture {
      completer = it
      "camera start"
    }
    return future to completer
  }

  @Test
  fun aBindThatWorks_reportsNothing() {
    var bound = false

    bindOrReport({ bound = true }, onError)

    assertEquals(true, bound)
    assertEquals(0, errors)
  }

  @Test
  fun aBindThatThrowsIllegalState_isReported() {
    bindOrReport({ throw IllegalStateException("no camera") }, onError)

    assertEquals(1, errors)
  }

  @Test
  fun aBindThatThrowsIllegalArgument_isReported() {
    bindOrReport({ throw IllegalArgumentException("no back camera") }, onError)

    assertEquals(1, errors)
  }

  @Test
  fun aFailedStart_isReportedOnce_whenItFails() {
    val (future, completer) = future()
    reportFailure(future, Runnable::run, onError)
    assertEquals(0, errors)

    completer.setException(IllegalStateException("camera in use"))

    assertEquals(1, errors)
  }

  @Test
  fun aStartThatWorks_reportsNothing() {
    val (future, completer) = future()
    reportFailure(future, Runnable::run, onError)

    completer.set(null)

    assertEquals(0, errors)
  }

  @Test
  fun aStartCancelledByLeavingTheScreen_reportsNothing_andDoesNotThrow() {
    // Run later, as the main thread would: an exception there would crash the app.
    val mainThread = mutableListOf<Runnable>()
    val (future, _) = future()
    reportFailure(future, mainThread::add, onError)

    future.cancel(false)
    mainThread.forEach { it.run() }

    assertEquals(1, mainThread.size)
    assertEquals(0, errors)
  }
}
