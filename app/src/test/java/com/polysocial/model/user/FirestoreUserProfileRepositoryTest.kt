// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial.model.user

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FirestoreUserProfileRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val users = mockk<CollectionReference>()
  private val ref = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val repository = FirestoreUserProfileRepository(db)

  private val profile =
      UserProfile(
          uid = "u1",
          email = "a@epfl.ch",
          displayName = "Test Student",
          section = "IN",
          year = "BA3",
      )

  init {
    every { db.collection(USERS_COLLECTION) } returns users
    every { users.document("u1") } returns ref
  }

  private fun offline() =
      FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)

  private fun denied() =
      FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

  /** A stored `users/{uid}` document with [fields], or a missing one when [fields] is null. */
  private fun snapshot(fields: Map<String, Any?>?): DocumentSnapshot = mockk {
    every { exists() } returns (fields != null)
    every { getString(any()) } answers { fields?.get(firstArg()) as String? }
    every { getTimestamp(any()) } answers { fields?.get(firstArg()) as Timestamp? }
  }

  private val storedFields =
      mapOf(
          "uid" to "u1",
          "email" to "a@epfl.ch",
          "displayName" to "Test Student",
          "section" to "IN",
          "year" to "BA3",
          "accountType" to "student",
          "createdAt" to Timestamp(1_700_000_000, 0),
      )

  /** Runs every transaction against [transaction], where `users/u1` is [existing]. */
  private fun transactionsSee(existing: DocumentSnapshot) {
    every { transaction.get(ref) } returns existing
    every { transaction.set(ref, any()) } returns transaction
    every { transaction.update(ref, any<Map<String, Any>>()) } returns transaction
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } answers
        {
          Tasks.forResult(firstArg<Transaction.Function<Boolean>>().apply(transaction))
        }
  }

  private fun transactionsFail(error: Exception) {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns
        Tasks.forException(error)
  }

  // ---- getProfile ----

  @Test
  fun getProfile_readsTheDocumentOfTheGivenUid() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields))

    val result = repository.getProfile("u1")

    assertEquals(
        ProfileResult.Found(profile.copy(createdAt = Instant.ofEpochSecond(1_700_000_000))),
        result,
    )
    verify { users.document("u1") }
  }

  @Test
  fun getProfile_readsAnAssociationAccount() = runTest {
    every { ref.get() } returns
        Tasks.forResult(snapshot(storedFields + ("accountType" to "association")))

    val result = repository.getProfile("u1") as ProfileResult.Found

    assertEquals(AccountType.ASSOCIATION, result.profile.accountType)
  }

  @Test
  fun getProfile_missingDocument_returnsNotFound() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(null))

    assertEquals(ProfileResult.NotFound, repository.getProfile("u1"))
  }

  @Test
  fun getProfile_pendingServerTimestamp_hasNoCreatedAt() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields - "createdAt"))

    assertEquals(ProfileResult.Found(profile), repository.getProfile("u1"))
  }

  @Test
  fun getProfile_documentMissingARequiredField_returnsUnexpectedError() = runTest {
    for (field in listOf("uid", "email", "displayName", "section", "year", "accountType")) {
      every { ref.get() } returns Tasks.forResult(snapshot(storedFields - field))

      assertEquals("without $field", ProfileResult.UnexpectedError, repository.getProfile("u1"))
    }
  }

  @Test
  fun getProfile_unknownAccountType_returnsUnexpectedError() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields + ("accountType" to "admin")))

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile("u1"))
  }

  @Test
  fun getProfile_offline_returnsNetworkError() = runTest {
    every { ref.get() } returns Tasks.forException(offline())

    assertEquals(ProfileResult.NetworkError, repository.getProfile("u1"))
  }

  @Test
  fun getProfile_deniedByTheRules_returnsUnexpectedError() = runTest {
    every { ref.get() } returns Tasks.forException(denied())

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile("u1"))
  }

  // ---- createProfile ----

  @Test
  fun createProfile_writesTheProfileToUsersUid() = runTest {
    transactionsSee(snapshot(null))
    val written = slot<Any>()
    every { transaction.set(ref, capture(written)) } returns transaction

    assertEquals(CreateProfileResult.Created, repository.createProfile(profile))

    verify { users.document("u1") }
    assertEquals(
        mapOf(
            "uid" to "u1",
            "email" to "a@epfl.ch",
            "displayName" to "Test Student",
            "section" to "IN",
            "year" to "BA3",
            "accountType" to "student",
            "createdAt" to FieldValue.serverTimestamp(),
        ),
        written.captured,
    )
  }

  @Test
  fun createProfile_neverWritesIsAssociationVerified() = runTest {
    transactionsSee(snapshot(null))
    val written = slot<Any>()
    every { transaction.set(ref, capture(written)) } returns transaction

    repository.createProfile(profile.copy(accountType = AccountType.ASSOCIATION))

    val fields = written.captured as Map<*, *>
    assertEquals("association", fields["accountType"])
    assertFalse(fields.containsKey("isAssociationVerified"))
  }

  @Test
  fun createProfile_existingProfile_returnsAlreadyExistsAndWritesNothing() = runTest {
    transactionsSee(snapshot(storedFields))

    assertEquals(CreateProfileResult.AlreadyExists, repository.createProfile(profile))

    verify(exactly = 0) { transaction.set(any(), any()) }
  }

  @Test
  fun createProfile_offline_returnsNetworkError() = runTest {
    transactionsFail(offline())

    assertEquals(CreateProfileResult.NetworkError, repository.createProfile(profile))
  }

  @Test
  fun createProfile_deniedByTheRules_returnsUnexpectedError() = runTest {
    transactionsFail(denied())

    assertEquals(CreateProfileResult.UnexpectedError, repository.createProfile(profile))
  }

  // ---- updateProfile ----

  @Test
  fun updateProfile_writesOnlyTheEditableFields() = runTest {
    transactionsSee(snapshot(storedFields))
    val written = slot<Map<String, Any>>()
    every { transaction.update(ref, capture(written)) } returns transaction

    val result =
        repository.updateProfile(
            profile.copy(displayName = "New Name", section = "SC", year = "MA1")
        )

    assertEquals(UpdateProfileResult.Updated, result)
    assertEquals(
        mapOf("displayName" to "New Name", "section" to "SC", "year" to "MA1"),
        written.captured,
    )
  }

  @Test
  fun updateProfile_missingProfile_returnsNotFoundAndWritesNothing() = runTest {
    transactionsSee(snapshot(null))

    assertEquals(UpdateProfileResult.NotFound, repository.updateProfile(profile))

    verify(exactly = 0) { transaction.update(any(), any<Map<String, Any>>()) }
  }

  @Test
  fun updateProfile_offline_returnsNetworkError() = runTest {
    transactionsFail(offline())

    assertEquals(UpdateProfileResult.NetworkError, repository.updateProfile(profile))
  }

  @Test
  fun updateProfile_deniedByTheRules_returnsUnexpectedError() = runTest {
    transactionsFail(denied())

    assertEquals(UpdateProfileResult.UnexpectedError, repository.updateProfile(profile))
  }
}
