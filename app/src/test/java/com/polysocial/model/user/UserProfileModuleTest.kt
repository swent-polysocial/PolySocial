// Contributors: Claude (wrote this test, #45; the Firestore provider, #156).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.firebase.ResettableFirestoreProvider
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileModuleTest {

  @Test
  fun theApp_storesProfilesInFirestore() {
    val repository =
        UserProfileModule.userProfileRepository(
            ResettableFirestoreProvider { mockk<FirebaseFirestore>() }
        )

    assertTrue(repository is FirestoreUserProfileRepository)
  }
}
