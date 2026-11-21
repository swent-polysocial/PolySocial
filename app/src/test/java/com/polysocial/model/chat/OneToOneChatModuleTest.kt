// Contributors: Claude
package com.polysocial.model.chat

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.FakeNetworkMonitor
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneToOneChatModuleTest {

  @Test
  fun theApp_storesOneToOneChatsInFirestore_checkingTheAppsConnection() = runTest {
    var resolved = 0
    val repository =
        OneToOneChatModule.oneToOneChatRepository(
            Provider {
              resolved++
              mockk<FirebaseFirestore>()
            },
            FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true)),
            FakeNetworkMonitor(online = false),
        )

    assertTrue(repository is FirestoreOneToOneChatRepository)
    // Logout replaces the Firestore instance (#156), so this singleton must not keep the first one:
    // it resolves Firestore only when a call reaches it.
    assertEquals(0, resolved)
    // Reaching the unstubbed Firestore mock would make this an UnexpectedError.
    assertEquals(SendMessageResult.NetworkError, repository.sendMessage("alice~bob", "Hi"))
    assertEquals(0, resolved)
  }
}
