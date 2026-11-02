// Contributors: Claude (wrote this test, #229).
package com.polysocial.model.user

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicProfileModuleTest {
  @Test
  fun theApp_readsPublicProfilesFromFirestore() {
    val repository = PublicProfileModule.publicProfileRepository(Provider { mockk() })

    assertTrue(repository is FirestorePublicProfileRepository)
  }

  @Test
  fun existingRepositoryReadsTheReplacementDatabaseAfterLogout() = runTest {
    val missing = mockk<DocumentSnapshot> { every { exists() } returns false }
    fun databaseWith(uid: String): FirebaseFirestore {
      val ref = mockk<DocumentReference> { every { get() } returns Tasks.forResult(missing) }
      val collection = mockk<CollectionReference> { every { document(uid) } returns ref }
      return mockk { every { collection(PUBLIC_PROFILES_COLLECTION) } returns collection }
    }
    var current = databaseWith("previous-student")
    val repository = PublicProfileModule.publicProfileRepository(Provider { current })
    assertEquals(PublicProfileResult.NotFound, repository.getPublicProfile("previous-student"))

    // The old instance knows no "next-student": reading it there would throw.
    current = databaseWith("next-student")

    assertEquals(PublicProfileResult.NotFound, repository.getPublicProfile("next-student"))
  }
}
