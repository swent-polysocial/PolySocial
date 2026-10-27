// Contributors: Claude (offline helper for the profile emulator tests, #119; Wi-Fi hint and
// failure reporting, #181; moved here to share it with the association tests, #126).
package com.polysocial.emulator

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import org.junit.Assert.fail

private const val TIMEOUT_MS = 30_000L
private const val POLL_MS = 250L

/**
 * Runs [block] with the device in airplane mode, so the emulators can't be reached, then turns the
 * network back on and waits until [reconnected] can be read from the server again.
 *
 * It assumes airplane mode turns Wi-Fi off. On a phone where Wi-Fi was once turned back on during
 * airplane mode, Android remembers it and keeps Wi-Fi on, so the device never goes offline.
 *
 * `FirebaseFirestore.disableNetwork()` is not enough: transactions still reach the server with it,
 * so they would succeed instead of failing (see CONTEXT.md, Traps).
 *
 * If [block] fails and the reconnection then times out too, [block]'s failure is reported, with the
 * timeout attached to it as suppressed.
 */
suspend fun <T> whileOffline(reconnected: DocumentReference, block: suspend () -> T): T {
  setAirplaneMode(true)
  // What block() threw, so that a reconnection timeout doesn't replace it. Failed assertions are
  // AssertionErrors, not Exceptions, hence the two catches.
  var failure: Throwable? = null
  try {
    waitUntil("the device to go offline (is Wi-Fi set to stay on in airplane mode?)") {
      !hasNetwork()
    }
    return block()
  } catch (e: Exception) {
    failure = e
    throw e
  } catch (e: AssertionError) {
    failure = e
    throw e
  } finally {
    setAirplaneMode(false)
    try {
      waitUntil("the emulators to be reachable again") {
        hasNetwork() && runCatchingFirestore { reconnected.get(Source.SERVER).await() }
      }
    } catch (timeout: AssertionError) {
      if (failure == null) throw timeout
      failure.addSuppressed(timeout)
    }
  }
}

private fun setAirplaneMode(enabled: Boolean) {
  val command = "cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}"
  val output =
      InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
  // Reading the output to its end waits until the command has finished.
  ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
}

private fun hasNetwork(): Boolean {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  return connectivity.activeNetwork != null
}

/** Whether [read] succeeds; a Firestore failure such as UNAVAILABLE counts as not yet. */
private suspend fun runCatchingFirestore(read: suspend () -> Unit): Boolean =
    try {
      read()
      true
    } catch (e: FirebaseFirestoreException) {
      false
    }

/** Polls [condition] until it holds, failing with a clear message after [TIMEOUT_MS]. */
private suspend fun waitUntil(what: String, condition: suspend () -> Boolean) {
  var waited = 0L
  while (!condition()) {
    if (waited >= TIMEOUT_MS) fail("Timed out after ${TIMEOUT_MS / 1000} s waiting for $what")
    delay(POLL_MS)
    waited += POLL_MS
  }
}
