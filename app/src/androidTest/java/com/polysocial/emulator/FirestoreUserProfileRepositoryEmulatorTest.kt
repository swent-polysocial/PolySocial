// Contributors: Claude (profile repository tests against the Firestore emulator, #119).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.USERS_COLLECTION
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreUserProfileRepository] against the Firestore and Auth emulators, so its
 * transactions, field names and types go through the real Firestore and the Security Rules (#35).
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreUserProfileRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy { FirestoreUserProfileRepository(emulator.firestore) }

  private fun document(uid: String): DocumentReference =
      emulator.firestore.collection(USERS_COLLECTION).document(uid)

  private suspend fun signInStudent(): UserProfile =
      student(emulator.signInVerifiedUser(STUDENT_EMAIL).uid)

  private suspend fun storedProfile(uid: String): UserProfile {
    val result = repository.getProfile(uid)
    assertTrue("Expected a stored profile, got $result", result is ProfileResult.Found)
    return (result as ProfileResult.Found).profile
  }

  @Test
  fun createProfile_createsOnce_thenReportsAlreadyExistsAndKeepsTheFirst() = runBlocking {
    val profile = signInStudent()

    assertEquals(CreateProfileResult.Created, repository.createProfile(profile))
    val first = storedProfile(profile.uid)

    assertEquals(
        CreateProfileResult.AlreadyExists,
        repository.createProfile(profile.copy(displayName = "Another Name", year = "MA1")),
    )
    assertEquals(first, storedProfile(profile.uid))
  }

  @Test
  fun createProfile_neverWritesTheVerifiedFlag() = runBlocking {
    val profile = signInStudent()

    assertEquals(
        CreateProfileResult.Created,
        repository.createProfile(profile.copy(isAssociationVerified = true)),
    )

    assertFalse(storedProfile(profile.uid).isAssociationVerified)
    assertFalse(document(profile.uid).get().await().contains("isAssociationVerified"))
  }

  @Test
  fun createProfile_forAnotherUid_isDeniedAndReportedAsUnexpectedError() = runBlocking {
    signInStudent()

    assertEquals(
        CreateProfileResult.UnexpectedError,
        repository.createProfile(student(OTHER_UID)),
    )
  }

  @Test
  fun updateProfile_withoutAProfile_returnsNotFoundAndCreatesNothing() = runBlocking {
    val profile = signInStudent()

    assertEquals(UpdateProfileResult.NotFound, repository.updateProfile(profile))
    assertEquals(ProfileResult.NotFound, repository.getProfile(profile.uid))
  }

  @Test
  fun updateProfile_changesOnlyTheEditableFields() = runBlocking {
    val profile = signInStudent()
    repository.createProfile(profile)
    val created = storedProfile(profile.uid)

    val result =
        repository.updateProfile(
            profile.copy(
                displayName = "Renamed Student",
                section = "SC",
                year = "MA1",
                email = "changed.email@epfl.ch",
                accountType = AccountType.ASSOCIATION,
                isAssociationVerified = true,
            )
        )

    assertEquals(UpdateProfileResult.Updated, result)
    assertEquals(
        created.copy(displayName = "Renamed Student", section = "SC", year = "MA1"),
        storedProfile(profile.uid),
    )
  }

  @Test
  fun getProfile_readsEveryStoredField_includingTheFlagAnAdminSet() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf(
            "uid" to uid,
            "email" to ASSOCIATION_EMAIL,
            "displayName" to "Test Association",
            "section" to "IN",
            "year" to "BA3",
            "accountType" to "association",
            "isAssociationVerified" to true,
            "createdAt" to CREATED_AT,
        ),
    )

    val stored = storedProfile(uid)

    assertEquals(
        UserProfile(
            uid = uid,
            email = ASSOCIATION_EMAIL,
            displayName = "Test Association",
            section = "IN",
            year = "BA3",
            accountType = AccountType.ASSOCIATION,
            isAssociationVerified = true,
            createdAt = CREATED_AT,
        ),
        stored,
    )
    assertTrue(stored.isVerifiedAssociation)
  }

  @Test
  fun rulesDenyAClientCreatingItsProfileAsVerified() = runBlocking {
    val profile = signInStudent()
    val fields = mapOf("uid" to profile.uid, "displayName" to profile.displayName)

    assertPermissionDenied { document(profile.uid).set(fields + VERIFIED).await() }

    // The same document without the flag is accepted, so the denial comes from the flag.
    document(profile.uid).set(fields).await()
    assertTrue(document(profile.uid).get().await().exists())
  }

  @Test
  fun rulesDenyAClientSettingTheVerifiedFlagOnItsProfile() = runBlocking {
    val profile = signInStudent()
    repository.createProfile(profile)

    assertPermissionDenied { document(profile.uid).update(VERIFIED).await() }

    assertFalse(storedProfile(profile.uid).isAssociationVerified)
    // An update of an editable field is accepted, so the denial comes from the flag.
    document(profile.uid).update("year", "MA1").await()
    assertEquals("MA1", storedProfile(profile.uid).year)
  }

  private suspend fun assertPermissionDenied(write: suspend () -> Unit) {
    try {
      write()
      fail("The Security Rules let a client set isAssociationVerified")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val OTHER_UID = "fictional-other-user"
    val CREATED_AT: Instant = Instant.ofEpochSecond(1_790_000_000)
    val VERIFIED = mapOf("isAssociationVerified" to true)

    fun student(uid: String) =
        UserProfile(
            uid = uid,
            email = STUDENT_EMAIL,
            displayName = "Test Student",
            section = "IN",
            year = "BA3",
        )
  }
}
