// Contributors: Claude (public profile tests against the Firestore emulator, #229; interests,
// #230; the missing public profile repaired at app start, test-first, #291).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.EnsurePublicProfileResult
import com.polysocial.model.user.FirestorePublicProfileRepository
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.PUBLIC_PROFILES_COLLECTION
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.model.user.USERS_COLLECTION
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
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
  fun theChosenInterests_areSavedInBothProfiles() = runBlocking {
    val email = FIRST_EMAIL
    val uid = emulator.signInVerifiedUser(email).uid
    val profile = student(uid, email, "First Student").copy(interests = setOf("tech", "music"))

    assertEquals(CreateProfileResult.Created, profiles.createProfile(profile))

    val stored = profiles.getProfile(uid) as ProfileResult.Found
    assertEquals(setOf("music", "tech"), stored.profile.interests)
    assertEquals(
        PublicProfileResult.Found(
            PublicProfile(uid, "First Student", "IN", "BA3", setOf("music", "tech"))
        ),
        publicProfiles.getPublicProfile(uid),
    )
  }

  @Test
  fun anEditOfTheInterests_changesThePublicProfileToo() = runBlocking {
    val student = studentWithProfile(FIRST_EMAIL, "First Student")

    assertEquals(
        UpdateProfileResult.Updated,
        profiles.updateProfile(student.copy(interests = setOf("cinema"))),
    )

    assertEquals(
        PublicProfileResult.Found(
            PublicProfile(student.uid, "First Student", "IN", "BA3", setOf("cinema"))
        ),
        publicProfiles.getPublicProfile(student.uid),
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

  /**
   * Signs in a new fictional account with [email] and stores its `users/{uid}` as the app saved it
   * before #229 (and #230): no public profile and no interests.
   */
  private suspend fun profileSavedBeforePublicProfiles(
      email: String,
      name: String,
      accountType: AccountType = AccountType.STUDENT,
  ): UserProfile {
    val uid = emulator.signInVerifiedUser(email).uid
    val legacy = student(uid, email, name).copy(accountType = accountType)
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf(
            "uid" to uid,
            "email" to email,
            "displayName" to legacy.displayName,
            "section" to legacy.section,
            "year" to legacy.year,
            "accountType" to accountType.value,
        ),
    )
    return legacy
  }

  @Test
  fun aProfileSavedBeforePublicProfiles_isMissing_untilItsNextSave() = runBlocking {
    val legacy = profileSavedBeforePublicProfiles(FIRST_EMAIL, "First Student")
    val uid = legacy.uid
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

  @Test
  fun ensurePublicProfile_givesAProfileSavedBeforePublicProfiles_itsCopy_withoutTheEmail() =
      runBlocking {
        val legacy = profileSavedBeforePublicProfiles(FIRST_EMAIL, "First Student")

        assertEquals(EnsurePublicProfileResult.Created, profiles.ensurePublicProfile(legacy.uid))

        assertEquals(
            PublicProfileResult.Found(PublicProfile(legacy.uid, "First Student", "IN", "BA3")),
            publicProfiles.getPublicProfile(legacy.uid),
        )
        val stored =
            emulator.firestore
                .collection(PUBLIC_PROFILES_COLLECTION)
                .document(legacy.uid)
                .get()
                .await()
        assertEquals(setOf("displayName", "section", "year", "interests"), stored.data?.keys)
        assertEquals(emptyList<String>(), stored.get("interests"))
        // The private profile is left as it was: still no interests field.
        val private =
            emulator.firestore.collection(USERS_COLLECTION).document(legacy.uid).get().await()
        assertFalse(private.contains("interests"))
      }

  @Test
  fun aRepairedPublicProfile_isReadByOtherStudents_forMatching() = runBlocking {
    val legacy = profileSavedBeforePublicProfiles(FIRST_EMAIL, "First Student")
    assertEquals(EnsurePublicProfileResult.Created, profiles.ensurePublicProfile(legacy.uid))

    studentWithProfile(SECOND_EMAIL, "Second Student")

    assertEquals(
        PublicProfilesResult.Found(
            mapOf(legacy.uid to PublicProfile(legacy.uid, "First Student", "IN", "BA3"))
        ),
        publicProfiles.getPublicProfiles(listOf(legacy.uid)),
    )
  }

  @Test
  fun ensurePublicProfile_twice_createsItOnce_andThenLeavesItAlone() = runBlocking {
    val legacy = profileSavedBeforePublicProfiles(FIRST_EMAIL, "First Student")

    assertEquals(EnsurePublicProfileResult.Created, profiles.ensurePublicProfile(legacy.uid))
    assertEquals(EnsurePublicProfileResult.NotNeeded, profiles.ensurePublicProfile(legacy.uid))
  }

  @Test
  fun ensurePublicProfile_aProfileSavedSincePublicProfiles_needsNothing() = runBlocking {
    // The unit tests check that nothing is written then; the rules would only accept an exact copy.
    val student = studentWithProfile(FIRST_EMAIL, "First Student")
    assertEquals(
        UpdateProfileResult.Updated,
        profiles.updateProfile(student.copy(interests = setOf("tech"))),
    )

    assertEquals(EnsurePublicProfileResult.NotNeeded, profiles.ensurePublicProfile(student.uid))

    assertEquals(
        PublicProfileResult.Found(
            PublicProfile(student.uid, "First Student", "IN", "BA3", setOf("tech"))
        ),
        publicProfiles.getPublicProfile(student.uid),
    )
  }

  @Test
  fun ensurePublicProfile_givesAnAssociationNone() = runBlocking {
    val club = profileSavedBeforePublicProfiles(ASSOCIATION_EMAIL, "", AccountType.ASSOCIATION)

    assertEquals(EnsurePublicProfileResult.NotNeeded, profiles.ensurePublicProfile(club.uid))

    assertFalse(emulator.existsAsAdmin(PUBLIC_PROFILES_COLLECTION, club.uid))
  }

  @Test
  fun ensurePublicProfile_anEpflAssociation_getsNoneEither() = runBlocking {
    // An @epfl.ch association is still an association (#161).
    val club = profileSavedBeforePublicProfiles(EPFL_ASSOCIATION_EMAIL, "", AccountType.ASSOCIATION)

    assertEquals(EnsurePublicProfileResult.NotNeeded, profiles.ensurePublicProfile(club.uid))

    assertFalse(emulator.existsAsAdmin(PUBLIC_PROFILES_COLLECTION, club.uid))
  }

  @Test
  fun ensurePublicProfile_withoutAPrivateProfile_returnsNotFound_andWritesNothing() = runBlocking {
    val uid = emulator.signInVerifiedUser(FIRST_EMAIL).uid

    assertEquals(EnsurePublicProfileResult.NotFound, profiles.ensurePublicProfile(uid))

    assertFalse(emulator.existsAsAdmin(PUBLIC_PROFILES_COLLECTION, uid))
  }

  @Test
  fun ensurePublicProfile_aDeletedAccount_isRefused_andGetsNone() = runBlocking {
    // deleteAccount's marker: the deleted user's ID token is still valid for up to an hour.
    val legacy = profileSavedBeforePublicProfiles(FIRST_EMAIL, "First Student")
    emulator.createAsAdmin(DELETED_ACCOUNTS_COLLECTION, legacy.uid, emptyMap())

    assertEquals(
        EnsurePublicProfileResult.UnexpectedError,
        profiles.ensurePublicProfile(legacy.uid),
    )

    assertFalse(emulator.existsAsAdmin(PUBLIC_PROFILES_COLLECTION, legacy.uid))
  }

  @Test
  fun ensurePublicProfile_forAnotherStudent_isRefused_andWritesNothing() = runBlocking {
    val other = profileSavedBeforePublicProfiles(SECOND_EMAIL, "Second Student")
    emulator.signInVerifiedUser(FIRST_EMAIL)

    // Reading another student's users/{uid} is denied, so the transaction fails before writing.
    assertEquals(
        EnsurePublicProfileResult.UnexpectedError,
        profiles.ensurePublicProfile(other.uid),
    )

    assertFalse(emulator.existsAsAdmin(PUBLIC_PROFILES_COLLECTION, other.uid))
  }

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val FIRST_EMAIL = "first.student@epfl.ch"
    const val SECOND_EMAIL = "second.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val EPFL_ASSOCIATION_EMAIL = "test.association@epfl.ch"

    /** Written only by deleteAccount; the literal name, as the Security Rules check it. */
    const val DELETED_ACCOUNTS_COLLECTION = "deletedAccounts"

    fun student(uid: String, email: String, name: String) =
        UserProfile(uid = uid, email = email, displayName = name, section = "IN", year = "BA3")
  }
}
