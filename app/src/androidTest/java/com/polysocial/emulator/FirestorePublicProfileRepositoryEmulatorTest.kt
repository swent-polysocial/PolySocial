// Contributors: Claude (public profile tests against the Firestore emulator, #229).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FirestorePublicProfileRepository
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.PUBLIC_PROFILES_COLLECTION
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.model.user.USERS_COLLECTION
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests public profiles (#229) against the Firestore and Auth emulators, so the private and public
 * profiles go through the real transactions and the real Security Rules: the profile step's write
 * creates both, an edit keeps them equal, another student reads the copy, and an association reads
 * none. The rules' every case is covered by firebase/firestore/firestore.rules.test.js.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestorePublicProfileRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val profiles by lazy { FirestoreUserProfileRepository(emulator.firestore) }
  private val publicProfiles by lazy { FirestorePublicProfileRepository(emulator.firestore) }

  /**
   * Signs in a new fictional student with [email] and saves their profile, as the profile step
   * does.
   */
  private suspend fun studentWithProfile(email: String, name: String): UserProfile {
    val profile = student(emulator.signInVerifiedUser(email).uid, email, name)
    assertEquals(CreateProfileResult.Created, profiles.createProfile(profile))
    return profile
  }

  @Test
  fun theProfileStepsWrite_givesOtherStudentsACopy() = runBlocking {
    val first = studentWithProfile(FIRST_EMAIL, "First Student")
    studentWithProfile(SECOND_EMAIL, "Second Student")

    assertEquals(
        PublicProfileResult.Found(PublicProfile(first.uid, "First Student", "IN", "BA3")),
        publicProfiles.getPublicProfile(first.uid),
    )
  }

  @Test
  fun anEdit_changesThePublicProfileToo() = runBlocking {
    val student = studentWithProfile(FIRST_EMAIL, "First Student")

    assertEquals(
        UpdateProfileResult.Updated,
        profiles.updateProfile(
            student.copy(displayName = "New Name", section = "SC", year = "MA1")
        ),
    )

    assertEquals(
        PublicProfileResult.Found(PublicProfile(student.uid, "New Name", "SC", "MA1")),
        publicProfiles.getPublicProfile(student.uid),
    )
  }

  @Test
  fun severalPublicProfiles_areReadTogether_andAMissingOneIsLeftOut() = runBlocking {
    val first = studentWithProfile(FIRST_EMAIL, "First Student")
    val second = studentWithProfile(SECOND_EMAIL, "Second Student")

    val result = publicProfiles.getPublicProfiles(listOf(first.uid, second.uid, "nobody"))

    assertEquals(
        PublicProfilesResult.Found(
            mapOf(
                first.uid to PublicProfile(first.uid, "First Student", "IN", "BA3"),
                second.uid to PublicProfile(second.uid, "Second Student", "IN", "BA3"),
            )
        ),
        result,
    )
  }

  @Test
  fun aProfileSavedBeforePublicProfiles_isMissing_untilItsNextSave() = runBlocking {
    val uid = emulator.signInVerifiedUser(FIRST_EMAIL).uid
    val legacy = student(uid, FIRST_EMAIL, "First Student")
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf(
            "uid" to uid,
            "email" to FIRST_EMAIL,
            "displayName" to legacy.displayName,
            "section" to legacy.section,
            "year" to legacy.year,
            "accountType" to "student",
        ),
    )
    assertEquals(PublicProfileResult.NotFound, publicProfiles.getPublicProfile(uid))

    assertEquals(UpdateProfileResult.Updated, profiles.updateProfile(legacy))

    assertEquals(
        PublicProfileResult.Found(PublicProfile(uid, "First Student", "IN", "BA3")),
        publicProfiles.getPublicProfile(uid),
    )
  }

  @Test
  fun anAssociation_getsNoPublicProfile_andReadsNone() = runBlocking {
    val student = studentWithProfile(FIRST_EMAIL, "First Student")
    val clubUid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    val club = student(clubUid, ASSOCIATION_EMAIL, "").copy(accountType = AccountType.ASSOCIATION)

    assertEquals(CreateProfileResult.Created, profiles.createProfile(club))

    assertFalse(emulator.existsAsAdmin(PUBLIC_PROFILES_COLLECTION, clubUid))
    assertEquals(PublicProfileResult.UnexpectedError, publicProfiles.getPublicProfile(student.uid))
    assertEquals(
        PublicProfilesResult.UnexpectedError,
        publicProfiles.getPublicProfiles(listOf(student.uid)),
    )
  }

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val FIRST_EMAIL = "first.student@epfl.ch"
    const val SECOND_EMAIL = "second.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"

    fun student(uid: String, email: String, name: String) =
        UserProfile(uid = uid, email = email, displayName = name, section = "IN", year = "BA3")
  }
}
