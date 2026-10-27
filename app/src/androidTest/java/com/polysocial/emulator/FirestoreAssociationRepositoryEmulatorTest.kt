// Contributors: Claude (association repository tests against the Firebase emulators, #126).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.polysocial.model.association.ASSOCIATIONS_COLLECTION
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.CreateAssociationResult
import com.polysocial.model.association.FirestoreAssociationRepository
import com.polysocial.model.association.UpdateAssociationResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreAssociationRepository] against the Firestore and Auth emulators, so its
 * transactions, field names and types go through the real Firestore and the Security Rules (#126).
 *
 * The rules are covered case by case in firebase/firestore/firestore.rules.test.js; here the real
 * repository shows that what it writes and reads is what the rules expect, for each kind of reader.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreAssociationRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy { FirestoreAssociationRepository(emulator.firestore) }

  private fun document(uid: String): DocumentReference =
      emulator.firestore.collection(ASSOCIATIONS_COLLECTION).document(uid)

  /**
   * Signs in a new association account and gives it the `users/{uid}` an association sign-up writes
   * (as an admin, so [verified] can be set too). Returns its UID.
   */
  private suspend fun signInAssociation(email: String, verified: Boolean = false): String {
    val uid = emulator.signInVerifiedUser(email).uid
    emulator.createAsAdmin(
        "users",
        uid,
        mapOf("uid" to uid, "accountType" to "association", "isAssociationVerified" to verified),
    )
    return uid
  }

  /** Signs in a new verified EPFL student, who has no association. */
  private suspend fun signInStudent() = emulator.signInVerifiedUser(STUDENT_EMAIL)

  /** Creates [association] as its signed-in owner, failing the test if the setup doesn't work. */
  private suspend fun create(association: Association) {
    assertEquals(CreateAssociationResult.Created, repository.createAssociation(association))
    assertTrue(emulator.existsAsAdmin(ASSOCIATIONS_COLLECTION, association.uid))
  }

  private suspend fun stored(uid: String): Association {
    val result = repository.getAssociation(uid)
    assertTrue("Expected a stored association, got $result", result is AssociationResult.Found)
    return (result as AssociationResult.Found).association
  }

  @Test
  fun pendingAssociation_createsItsOwnAssociation_withTheRulesFieldsAndAServerTimestamp() =
      runBlocking {
        val uid = signInAssociation(ASSOCIATION_EMAIL)

        assertEquals(CreateAssociationResult.Created, repository.createAssociation(club(uid)))

        val data = document(uid).get().await().data!!
        assertEquals(setOf("name", "type", "about", "website", "createdAt"), data.keys)
        assertEquals("CLUB", data["type"])
        assertTrue("createdAt is ${data["createdAt"]}", data["createdAt"] is Timestamp)
        val association = stored(uid)
        assertEquals(club(uid), association.copy(createdAt = null))
        assertNotNull(association.createdAt)
      }

  @Test
  fun createAssociation_twice_reportsAlreadyExistsAndKeepsTheFirst() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL)
    create(club(uid))
    val first = stored(uid)

    val second = club(uid).copy(name = "Another Name", about = "Another about.")
    assertEquals(CreateAssociationResult.AlreadyExists, repository.createAssociation(second))

    assertEquals(first, stored(uid))
  }

  @Test
  fun createAssociation_withoutAWebsite_storesNone() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL)

    assertEquals(
        CreateAssociationResult.Created,
        repository.createAssociation(club(uid).copy(website = null)),
    )

    assertFalse(document(uid).get().await().contains("website"))
    assertEquals(null, stored(uid).website)
  }

  @Test
  fun createAssociation_invalidFields_areRefusedByTheRules() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL)

    for (invalid in
        listOf(
            club(uid).copy(name = "   "),
            club(uid).copy(name = "a".repeat(61)),
            club(uid).copy(about = ""),
            club(uid).copy(website = "http://club.example.org"),
        )) {
      assertEquals(
          "Creating $invalid",
          CreateAssociationResult.UnexpectedError,
          repository.createAssociation(invalid),
      )
    }
    assertEquals(AssociationResult.NotFound, repository.getAssociation(uid))
  }

  @Test
  fun pendingAssociation_editsEveryField_andRemovesItsWebsite() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL)
    create(club(uid))
    val createdAt = stored(uid).createdAt
    val edited =
        club(uid)
            .copy(
                name = "Renamed Club",
                type = AssociationType.COMMISSION,
                about = "New about.",
                website = null,
            )

    assertEquals(UpdateAssociationResult.Updated, repository.updateAssociation(edited))

    assertEquals(edited.copy(createdAt = createdAt), stored(uid))
    assertFalse(document(uid).get().await().contains("website"))
  }

  @Test
  fun updateAssociation_beforeCreating_returnsNotFoundAndCreatesNothing() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL)

    assertEquals(UpdateAssociationResult.NotFound, repository.updateAssociation(club(uid)))

    assertEquals(AssociationResult.NotFound, repository.getAssociation(uid))
  }

  @Test
  fun verifiedAssociation_editsItsAbout_butCantRenameItselfOrChangeItsType() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL, verified = true)
    create(club(uid))

    assertEquals(
        UpdateAssociationResult.UnexpectedError,
        repository.updateAssociation(club(uid).copy(name = "Another Club")),
    )
    assertEquals(
        UpdateAssociationResult.UnexpectedError,
        repository.updateAssociation(club(uid).copy(type = AssociationType.EPFL_UNIT)),
    )
    assertEquals(
        UpdateAssociationResult.Updated,
        repository.updateAssociation(club(uid).copy(about = "New about.")),
    )

    val association = stored(uid)
    assertEquals("Test Club", association.name)
    assertEquals(AssociationType.CLUB, association.type)
    assertEquals("New about.", association.about)
  }

  @Test
  fun student_readsAVerifiedAssociation_butAPendingOneLooksMissing() = runBlocking {
    val verified = signInAssociation(VERIFIED_EMAIL, verified = true)
    create(club(verified))
    val pending = signInAssociation(ASSOCIATION_EMAIL)
    create(club(pending))

    signInStudent()

    assertEquals(club(verified), (stored(verified)).copy(createdAt = null))
    assertEquals(AssociationResult.NotFound, repository.getAssociation(pending))
  }

  @Test
  fun anotherAssociation_cantReadAVerifiedAssociation() = runBlocking {
    val verified = signInAssociation(VERIFIED_EMAIL, verified = true)
    create(club(verified))

    signInAssociation(ASSOCIATION_EMAIL, verified = true)

    assertEquals(AssociationResult.NotFound, repository.getAssociation(verified))
  }

  @Test
  fun student_cantCreateAnAssociation() = runBlocking {
    val uid = signInStudent().uid

    assertEquals(CreateAssociationResult.UnexpectedError, repository.createAssociation(club(uid)))

    assertFalse(emulator.existsAsAdmin(ASSOCIATIONS_COLLECTION, uid))
  }

  @Test
  fun createAssociation_offline_returnsNetworkErrorAndQueuesNothing() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL)

    val result = whileOffline(document(uid)) { repository.createAssociation(club(uid)) }

    assertEquals(CreateAssociationResult.NetworkError, result)
    // Back online, nothing was written later from a queue.
    assertEquals(AssociationResult.NotFound, repository.getAssociation(uid))
  }

  @Test
  fun updateAssociation_offline_returnsNetworkErrorAndQueuesNothing() = runBlocking {
    val uid = signInAssociation(ASSOCIATION_EMAIL)
    create(club(uid))

    val result =
        whileOffline(document(uid)) {
          repository.updateAssociation(club(uid).copy(about = "Offline about."))
        }

    assertEquals(UpdateAssociationResult.NetworkError, result)
    assertEquals(club(uid).about, stored(uid).about)
  }

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val VERIFIED_EMAIL = "test.verified@example.com"

    fun club(uid: String) =
        Association(
            uid = uid,
            name = "Test Club",
            type = AssociationType.CLUB,
            about = "A fictional club for tests.",
            website = "https://club.example.org",
        )
  }
}
