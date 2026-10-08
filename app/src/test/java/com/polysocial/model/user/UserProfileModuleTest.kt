// Contributors: Claude (wrote this test, #45).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileModuleTest {

  @Test
  fun theApp_storesProfilesInFirestore() {
    val repository = UserProfileModule.userProfileRepository(mockk<FirebaseFirestore>())

    assertTrue(repository is FirestoreUserProfileRepository)
  }
}
