// Contributors: Claude (Opus 5.5, Anthropic) (wrote this test, #467).
package com.polysocial.model.checkin

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.mockk
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
}
