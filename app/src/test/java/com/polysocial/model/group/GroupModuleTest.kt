// Contributors: Claude (Opus 5.5, Anthropic) (wrote this test, #232).
package com.polysocial.model.group

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.mockk
import javax.inject.Provider
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupModuleTest {

  @Test
  fun theApp_storesGroupsInFirestore() {
    val offline =
        object : NetworkMonitor {
          override fun isOnline() = false
        }

    val repository =
        GroupModule.groupRepository(
            Provider { mockk<FirebaseFirestore>() },
            FakeAuthRepository(),
            offline,
        )

    assertTrue(repository is FirestoreGroupRepository)
  }
}
