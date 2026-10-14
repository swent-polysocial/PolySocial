// Contributors: OpenAI Codex (adapt to replaceable Firestore provider, #109).
// Contributors: Claude (wrote this test, #45).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileModuleTest {

  @Test
  fun theApp_storesProfilesInFirestore() {
    val repository =
        UserProfileModule.userProfileRepository(
            javax.inject.Provider { mockk<FirebaseFirestore>() }
        )

    assertTrue(repository is FirestoreUserProfileRepository)
  }
}
