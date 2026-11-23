// Contributors: Claude (Opus 5.5, Anthropic) (username repository tests against the rules, #448).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FirestorePublicProfileRepository
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.FirestoreUsernameRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.SetUsernameResult
import com.polysocial.model.user.USERNAMES_COLLECTION
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UsernameLookupResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreUsernameRepository] against the Firestore and Auth emulators, so its transaction
 * goes through the real Firestore and the username rules (#448), together with the profile
 * repositories that read and keep the name. The rules' cases are covered in detail by
 * firebase/firestore/firestore.rules.test.js.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreUsernameRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val usernames by lazy {
    FirestoreUsernameRepository(
        db = emulator.firestore,
        auth = EmulatorSignedInUser(emulator),
        network = AlwaysOnline,
    )
  }
  private val profiles by lazy { FirestoreUserProfileRepository(emulator.firestore) }
  private val publicProfiles by lazy { FirestorePublicProfileRepository(emulator.firestore) }

  /** Signs a fictional student in and saves their profile as the profile step does. */
  private suspend fun signInStudentWithProfile(): UserProfile {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    val profile = UserProfile(uid, STUDENT_EMAIL, "Test Student", "IN", "BA3")
    assertEquals(CreateProfileResult.Created, timed { profiles.createProfile(profile) })
    return profile
  }

  private suspend fun storedUsername(uid: String): String? =
      (timed { profiles.getProfile(uid) } as ProfileResult.Found).profile.username

  private suspend fun publicUsername(uid: String): String? =
      (timed { publicProfiles.getPublicProfile(uid) } as PublicProfileResult.Found).profile.username

  private suspend fun <T> timed(call: suspend () -> T): T = withTimeout(TIMEOUT_MS) { call() }

  @Test
  fun aClaimedName_isInBothProfiles_andLooksUpToTheStudent() = runBlocking {
    val uid = signInStudentWithProfile().uid

    assertEquals(SetUsernameResult.Saved, timed { usernames.setUsername("franek") })

    assertEquals("franek", storedUsername(uid))
    assertEquals("franek", publicUsername(uid))
    assertEquals(UsernameLookupResult.Found(uid), timed { usernames.lookUp("franek") })
  }

  @Test
  fun aRename_freesThePreviousName() = runBlocking {
    val uid = signInStudentWithProfile().uid
    timed { usernames.setUsername("franek") }

    assertEquals(SetUsernameResult.Saved, timed { usernames.setUsername("franek.n") })

    assertEquals(UsernameLookupResult.NotFound, timed { usernames.lookUp("franek") })
    assertEquals(UsernameLookupResult.Found(uid), timed { usernames.lookUp("franek.n") })
    assertEquals("franek.n", publicUsername(uid))
  }

  @Test
  fun aNameAnotherStudentHolds_isTaken_andNothingChanges() = runBlocking {
    val uid = signInStudentWithProfile().uid
    emulator.createAsAdmin(USERNAMES_COLLECTION, "lea", mapOf("uid" to OTHER_UID))

    assertEquals(SetUsernameResult.Taken, timed { usernames.setUsername("lea") })

    assertEquals(null, storedUsername(uid))
    assertEquals(UsernameLookupResult.Found(OTHER_UID), timed { usernames.lookUp("lea") })
  }

  @Test
  fun editingTheProfile_keepsTheUsernameInThePublicCopy() = runBlocking {
    val profile = signInStudentWithProfile()
    timed { usernames.setUsername("franek") }

    // The caller's profile was read before the name was claimed: its username is null.
    assertEquals(
        UpdateProfileResult.Updated,
        timed { profiles.updateProfile(profile.copy(displayName = "New Name")) },
    )

    assertEquals("franek", storedUsername(profile.uid))
    assertEquals("franek", publicUsername(profile.uid))
  }

  @Test
  fun aFreeName_isNotFound_andNoDocumentIsCreated() = runBlocking {
    signInStudentWithProfile()

    assertEquals(UsernameLookupResult.NotFound, timed { usernames.lookUp("nobody") })

    assertFalse(emulator.existsAsAdmin(USERNAMES_COLLECTION, "nobody"))
  }

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val OTHER_UID = "fictional-other"
    const val TIMEOUT_MS = 10_000L
  }
}
