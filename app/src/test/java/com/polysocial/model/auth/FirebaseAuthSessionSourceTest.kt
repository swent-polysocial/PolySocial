// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests, #198; Mohamed Khellaf (reviewed and
// revised).
package com.polysocial.model.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseAuthSessionSourceTest {
  private val auth = mockk<FirebaseAuth>()
  private val listener = slot<FirebaseAuth.AuthStateListener>()
  private var signedIn: FirebaseUser? = null

  init {
    every { auth.currentUser } answers { signedIn }
    // Like Firebase: the listener is called once when it is added, then after each change.
    every { auth.addAuthStateListener(capture(listener)) } answers
        {
          listener.captured.onAuthStateChanged(auth)
        }
    every { auth.removeAuthStateListener(any()) } just runs
  }

  private fun user(uid: String): FirebaseUser = mockk { every { this@mockk.uid } returns uid }

  private fun change(user: FirebaseUser?) {
    signedIn = user
    listener.captured.onAuthStateChanged(auth)
  }

  @Test
  fun emitsTheCurrentAccountThenEachChange_andStopsListeningWhenCancelled() = runTest {
    signedIn = user("student-a")
    val seen = mutableListOf<String?>()

    val collecting =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          FirebaseAuthSessionSource { auth }.uidChanges().collect { seen += it }
        }
    change(null)
    change(user("student-b"))

    assertEquals(listOf("student-a", null, "student-b"), seen)
    collecting.cancel()
    runCurrent()
    verify(exactly = 1) { auth.removeAuthStateListener(listener.captured) }
  }

  @Test
  fun slowCollector_getsTheLatestAccount() = runTest {
    val seen = mutableListOf<String?>()
    val busy = CompletableDeferred<Unit>()
    val collecting = launch {
      FirebaseAuthSessionSource { auth }
          .uidChanges()
          .collect {
            seen += it
            busy.await()
          }
    }
    runCurrent()

    // Two changes while the collector is still busy with the first account.
    change(user("student-a"))
    change(user("student-b"))
    busy.complete(Unit)
    runCurrent()

    assertEquals(listOf(null, "student-b"), seen)
    collecting.cancel()
  }
}
