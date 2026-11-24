// Contributors: Claude (Opus 5.5, Anthropic) (wrote this test, #467).
package com.polysocial.model.checkin

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInModuleTest {

  @Test
  fun theApp_storesCheckInsInFirestore() = runTest {
    val offline =
        object : NetworkMonitor {
          override fun isOnline() = false
        }

    val repository =
        CheckInModule.checkInRepository(
            Provider { mockk<FirebaseFirestore>() },
            FakeAuthRepository(),
            offline,
            Clock.systemUTC(),
        )

    assertTrue(repository is FirestoreCheckInRepository)
    // Its dependencies are the ones given: nobody signed in comes from the auth passed in.
    assertEquals(CheckInResult.NotSignedIn, repository.checkIn("event-1"))
  }

  @Test
  fun anExistingRepositoryUsesTheReplacementDatabaseAfterLogout() = runTest {
    // Logout replaces the Firestore instance (#156); a singleton repository must follow it.
    val old =
        mockk<FirebaseFirestore> {
          every { collection(EVENTS_COLLECTION) } throws IllegalStateException("terminated")
        }
    val fresh =
        mockk<FirebaseFirestore> {
          every { collection(EVENTS_COLLECTION) } throws IllegalStateException("not seeded")
        }
    var current = old
    val online =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val repository =
        CheckInModule.checkInRepository(
            Provider { current },
            FakeAuthRepository(AuthUser("student", "student@epfl.ch", true)),
            online,
            Clock.systemUTC(),
        )

    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))
    current = fresh
    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))

    verify(exactly = 1) { old.collection(EVENTS_COLLECTION) }
    verify(exactly = 1) { fresh.collection(EVENTS_COLLECTION) }
  }
}
