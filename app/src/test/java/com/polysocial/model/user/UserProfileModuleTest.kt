// Contributors: OpenAI Codex (verify profile isolation through replaceable Firestore provider,
// #109).
// Contributors: Claude (wrote this test, #45).
package com.polysocial.model.user

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.*
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileModuleTest {

  @Test
  fun existingRepositoryReadsReplacementDatabaseAfterLogout() = runTest {
    val old = mockk<FirebaseFirestore>()
    val fresh = mockk<FirebaseFirestore>()
    var current = old
    val oldUsers = mockk<CollectionReference>()
    val freshUsers = mockk<CollectionReference>()
    val oldRef = mockk<DocumentReference>()
    val freshRef = mockk<DocumentReference>()
    val missing = mockk<DocumentSnapshot>()
    every { missing.exists() } returns false
    every { old.collection(USERS_COLLECTION) } returns oldUsers
    every { fresh.collection(USERS_COLLECTION) } returns freshUsers
    every { oldUsers.document("previous-student") } returns oldRef
    every { freshUsers.document("next-student") } returns freshRef
    val previousProfile =
        UserProfile("previous-student", "fictional@epfl.ch", "Test Student", "IN", "BA1")
    val fields =
        mapOf(
            "uid" to previousProfile.uid,
            "email" to previousProfile.email,
            "displayName" to previousProfile.displayName,
            "section" to previousProfile.section,
            "year" to previousProfile.year,
            "accountType" to "student",
        )
    val previous = mockk<DocumentSnapshot>()
    every { previous.exists() } returns true
    every { previous.getString(any()) } answers { fields[firstArg<String>()] }
    every { previous.get("isAssociationVerified") } returns false
    every { previous.getTimestamp("createdAt") } returns null
    every { oldRef.get() } returns Tasks.forResult(previous)
    every { freshRef.get() } returns Tasks.forResult(missing)
    val repository = UserProfileModule.userProfileRepository(Provider { current })
    assertEquals(ProfileResult.Found(previousProfile), repository.getProfile("previous-student"))
    current = fresh
    assertEquals(ProfileResult.NotFound, repository.getProfile("next-student"))
    verify(exactly = 1) { old.collection(USERS_COLLECTION) }
    verify(exactly = 1) { fresh.collection(USERS_COLLECTION) }
    verify(exactly = 1) { freshRef.get() }
  }

  @Test
  fun theApp_storesProfilesInFirestore() {
    val repository =
        UserProfileModule.userProfileRepository(
            javax.inject.Provider { mockk<FirebaseFirestore>() }
        )

    assertTrue(repository is FirestoreUserProfileRepository)
  }
}
