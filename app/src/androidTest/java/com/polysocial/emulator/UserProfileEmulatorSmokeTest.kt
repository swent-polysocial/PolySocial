// Contributors: Claude (Firestore emulator smoke test, #117; geopoints and nulls after review;
// Firestore from a provider, #156).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.GeoPoint
import com.polysocial.model.firebase.ResettableFirestoreProvider
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.USERS_COLLECTION
import com.polysocial.model.user.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the emulator setup end to end: a fictional verified EPFL student writes their own
 * `users/{uid}` through the real Security Rules and reads it back.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class UserProfileEmulatorSmokeTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy {
    FirestoreUserProfileRepository(ResettableFirestoreProvider { emulator.firestore })
  }

  @Test
  fun verifiedStudentWritesAndReadsBackTheirOwnProfile() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    val profile =
        UserProfile(
            uid = uid,
            email = STUDENT_EMAIL,
            displayName = "Test Student",
            section = "IN",
            year = "BA3",
        )

    assertEquals(CreateProfileResult.Created, repository.createProfile(profile))

    val found = repository.getProfile(uid)
    assertTrue("Expected the profile back, got $found", found is ProfileResult.Found)
    val stored = (found as ProfileResult.Found).profile
    assertEquals(profile, stored.copy(createdAt = null))
    // The server timestamp proves the write reached the emulator, not only the local cache.
    assertNotNull(stored.createdAt)
  }

  @Test
  fun rulesDenyReadingAnotherUsersProfile() = runBlocking {
    emulator.createAsAdmin(USERS_COLLECTION, OTHER_UID, mapOf("uid" to OTHER_UID))
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    emulator.createAsAdmin(USERS_COLLECTION, uid, mapOf("uid" to uid))
    // The student can read their own profile, so the denial below comes from the other uid, not
    // from an unverified sign-in that every rule would reject.
    assertTrue(emulator.firestore.collection(USERS_COLLECTION).document(uid).get().await().exists())

    try {
      emulator.firestore.collection(USERS_COLLECTION).document(OTHER_UID).get().await()
      fail("The Security Rules let a student read another user's profile")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  @Test
  fun adminWritesKeepTheirValueTypes() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf(
            "uid" to uid,
            "displayName" to "Name with \"quotes\"",
            "isAssociationVerified" to true,
            "count" to 3,
            "createdAt" to CREATED_AT,
            "allowedUids" to listOf(uid, OTHER_UID),
            "location" to LOCATION,
            "endTime" to null,
        ),
    )

    val stored = emulator.firestore.collection(USERS_COLLECTION).document(uid).get().await()
    assertEquals("Name with \"quotes\"", stored.getString("displayName"))
    assertEquals(true, stored.getBoolean("isAssociationVerified"))
    assertEquals(3L, stored.getLong("count"))
    assertEquals(CREATED_AT, stored.getTimestamp("createdAt"))
    assertEquals(listOf(uid, OTHER_UID), stored.get("allowedUids"))
    assertEquals(LOCATION, stored.getGeoPoint("location"))
    // Stored as a null field, not left out, as Event.toFirestoreMap() writes a missing end time.
    assertTrue(stored.contains("endTime"))
    assertNull(stored.get("endTime"))
  }

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val OTHER_UID = "fictional-other-user"
    val CREATED_AT = Timestamp(1_790_000_000, 0)
    val LOCATION = GeoPoint(46.5191, 6.5668)
  }
}
