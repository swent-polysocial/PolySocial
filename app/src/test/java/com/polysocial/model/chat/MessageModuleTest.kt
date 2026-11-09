// Contributors: Claude (Opus 5.5, Anthropic) (wrote this test, #237).
package com.polysocial.model.chat

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageModuleTest {

  @Test
  fun theApp_storesMessagesInFirestore_checkingTheAppsConnection() = runTest {
    val offline =
        object : NetworkMonitor {
          override fun isOnline() = false
        }

    val repository =
        MessageModule.messageRepository(
            Provider { mockk<FirebaseFirestore>() },
            FakeAuthRepository(AuthUser("student", "student@epfl.ch", true)),
            offline,
        )

    assertTrue(repository is FirestoreMessageRepository)
    // Reaching the unstubbed Firestore mock would make this an UnexpectedError.
    assertEquals(SendMessageResult.NetworkError, repository.sendMessage("event-1", "group-1", "Hi"))
  }
}
