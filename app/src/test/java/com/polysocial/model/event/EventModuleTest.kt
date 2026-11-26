// Contributors: OpenAI Codex (adapt to replaceable Firestore provider, #109).
// Contributors: Claude (wrote this test; shared Firestore instance and profiles, #45).
// Contributors: Claude (Sonnet 5.5, Anthropic) (the public profile argument, #369).
package com.polysocial.model.event

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
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
            javax.inject.Provider { mockk<FirebaseFirestore>() },
            FakeAuthRepository(),
            FakeUserProfileRepository(),
            FakePublicProfileRepository(),
            offline,
            Clock.systemUTC(),
        )

    assertTrue(repository is FirestoreEventRepository)
  }
}
