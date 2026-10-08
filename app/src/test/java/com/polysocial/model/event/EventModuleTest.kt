// Contributors: Claude (wrote this test; shared Firestore instance and profiles, #45).
package com.polysocial.model.event

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.FakeUserProfileRepository
import io.mockk.mockk
import java.time.Clock
import org.junit.Assert.assertTrue
import org.junit.Test

class EventModuleTest {

  @Test
  fun theApp_createsEventsInFirestore() {
    val offline =
        object : NetworkMonitor {
          override fun isOnline() = false
        }

    val repository =
        EventModule.eventRepository(
            mockk<FirebaseFirestore>(),
            FakeAuthRepository(),
            FakeUserProfileRepository(),
            offline,
            Clock.systemUTC(),
        )

    assertTrue(repository is FirestoreEventRepository)
  }
}
