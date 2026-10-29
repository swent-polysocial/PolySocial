// Contributors: Claude Opus 5.5 (wrote this test, #121).
package com.polysocial.model.registration

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.mockk
import javax.inject.Provider
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationModuleTest {

  @Test
  fun theApp_storesRegistrationsInFirestore() {
    val offline =
        object : NetworkMonitor {
          override fun isOnline() = false
        }

    val repository =
        RegistrationModule.registrationRepository(
            Provider { mockk<FirebaseFirestore>() },
            FakeAuthRepository(),
            offline,
        )

    assertTrue(repository is FirestoreRegistrationRepository)
  }
}
