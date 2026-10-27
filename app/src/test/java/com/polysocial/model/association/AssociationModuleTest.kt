// Contributors: Claude (wrote this test, #126).
package com.polysocial.model.association

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AssociationModuleTest {

  /** A Firestore instance where `associations/{uid}` is missing. */
  private fun databaseWithout(uid: String): FirebaseFirestore {
    val missing = mockk<DocumentSnapshot> { every { exists() } returns false }
    val ref = mockk<DocumentReference> { every { get() } returns Tasks.forResult(missing) }
    val collection = mockk<CollectionReference> { every { document(uid) } returns ref }
    return mockk { every { collection(ASSOCIATIONS_COLLECTION) } returns collection }
  }

  @Test
  fun existingRepositoryReadsTheReplacementDatabaseAfterLogout() = runTest {
    // Logout replaces the Firestore instance (#156); a singleton repository must follow it.
    val old = databaseWithout("previous-club")
    val fresh = databaseWithout("next-club")
    var current = old
    val repository = AssociationModule.associationRepository(Provider { current })

    assertEquals(AssociationResult.NotFound, repository.getAssociation("previous-club"))
    current = fresh
    assertEquals(AssociationResult.NotFound, repository.getAssociation("next-club"))

    verify(exactly = 1) { old.collection(ASSOCIATIONS_COLLECTION) }
    verify(exactly = 1) { fresh.collection(ASSOCIATIONS_COLLECTION) }
  }
}
