// Contributors: Claude (wrote these tests, #126).
package com.polysocial.model.association

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreAssociationRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val associations = mockk<CollectionReference>()
  private val ref = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val repository = FirestoreAssociationRepository(db)

  /** A fictional association: no real data in tests. */
  private val association =
      Association(
          uid = "club1",
          name = "Test Club",
          type = AssociationType.CLUB,
          about = "A fictional club for tests.",
          website = "https://club.example.org",
      )

  init {
    // The literal name, not the constant: it is the collection the Security Rules protect.
    every { db.collection("associations") } returns associations
    every { associations.document("club1") } returns ref
  }

  private fun firestoreError(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException(code.name, code)

  private fun offline() = firestoreError(FirebaseFirestoreException.Code.UNAVAILABLE)

  private fun denied() = firestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED)

  /** What Firestore throws when it is used after being shut down: not a FirebaseException. */
  private fun terminated() = IllegalStateException("The client has already been terminated")

  /** A stored `associations/club1` document with [fields], or a missing one when null. */
  private fun snapshot(fields: Map<String, Any?>?): DocumentSnapshot = mockk {
    every { id } returns "club1"
    every { exists() } returns (fields != null)
    every { getString(any()) } answers { fields?.get(firstArg()) as String? }
    every { getTimestamp(any()) } answers { fields?.get(firstArg()) as Timestamp? }
  }

  private val storedFields =
      mapOf(
          "name" to "Test Club",
          "type" to "CLUB",
          "about" to "A fictional club for tests.",
          "website" to "https://club.example.org",
          "createdAt" to Timestamp(1_790_000_000, 0),
      )

  /** Runs every transaction against [transaction], where `associations/club1` is [existing]. */
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

  // ---- getAssociation ----

  @Test
  fun getAssociation_readsTheDocumentOfTheGivenUid() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields))

    val result = repository.getAssociation("club1")

    assertEquals(
        AssociationResult.Found(association.copy(createdAt = Instant.ofEpochSecond(1_790_000_000))),
        result,
    )
    verify { associations.document("club1") }
  }

  @Test
  fun getAssociation_readsEveryType() = runTest {
    for (type in AssociationType.entries) {
      every { ref.get() } returns Tasks.forResult(snapshot(storedFields + ("type" to type.name)))

      val result = repository.getAssociation("club1") as AssociationResult.Found

      assertEquals(type, result.association.type)
    }
  }

  @Test
  fun getAssociation_withoutAWebsite_hasNone() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields - "website"))

    val result = repository.getAssociation("club1") as AssociationResult.Found

    assertNull(result.association.website)
  }

  @Test
  fun getAssociation_pendingServerTimestamp_hasNoCreatedAt() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields - "createdAt"))

    val result = repository.getAssociation("club1") as AssociationResult.Found

    assertNull(result.association.createdAt)
  }

  @Test
  fun getAssociation_missingDocument_returnsNotFound() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(null))

    assertEquals(AssociationResult.NotFound, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_deniedByTheRules_returnsNotFound() = runTest {
    // A student reading an association an admin hasn't verified yet: it must look missing.
    every { ref.get() } returns Tasks.forException(denied())

    assertEquals(AssociationResult.NotFound, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_documentMissingARequiredField_returnsUnexpectedError() = runTest {
    for (field in listOf("name", "type", "about")) {
      every { ref.get() } returns Tasks.forResult(snapshot(storedFields - field))

      assertEquals(
          "Without $field",
          AssociationResult.UnexpectedError,
          repository.getAssociation("club1"),
      )
    }
  }

  @Test
  fun getAssociation_unknownType_returnsUnexpectedError() = runTest {
    for (type in listOf("UNION", "club", "")) {
      every { ref.get() } returns Tasks.forResult(snapshot(storedFields + ("type" to type)))

      assertEquals(
          "Type $type",
          AssociationResult.UnexpectedError,
          repository.getAssociation("club1"),
      )
    }
  }

  @Test
  fun getAssociation_fieldOfTheWrongType_returnsUnexpectedError() = runTest {
    for (field in listOf("name", "type", "about", "website")) {
      val stored = snapshot(storedFields)
      every { stored.getString(field) } throws RuntimeException("$field is not a String")
      every { ref.get() } returns Tasks.forResult(stored)

      assertEquals(
          "Wrong-typed $field",
          AssociationResult.UnexpectedError,
          repository.getAssociation("club1"),
      )
    }
  }

  @Test
  fun getAssociation_createdAtOfTheWrongType_returnsUnexpectedError() = runTest {
    val stored = snapshot(storedFields)
    every { stored.getTimestamp("createdAt") } throws RuntimeException("not a Timestamp")
    every { ref.get() } returns Tasks.forResult(stored)

    assertEquals(AssociationResult.UnexpectedError, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_offline_returnsNetworkError() = runTest {
    every { ref.get() } returns Tasks.forException(offline())

    assertEquals(AssociationResult.NetworkError, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_otherFirestoreFailure_returnsUnexpectedError() = runTest {
    every { ref.get() } returns
        Tasks.forException(firestoreError(FirebaseFirestoreException.Code.INTERNAL))

    assertEquals(AssociationResult.UnexpectedError, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_otherFirebaseFailure_returnsUnexpectedError() = runTest {
    // A FirebaseException that isn't Firestore's: only Firestore's UNAVAILABLE means offline.
    every { ref.get() } returns Tasks.forException(FirebaseNetworkException("network"))

    assertEquals(AssociationResult.UnexpectedError, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_cancelledTask_returnsUnexpectedError() = runTest {
    every { ref.get() } returns Tasks.forCanceled()

    assertEquals(AssociationResult.UnexpectedError, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_unexpectedException_returnsUnexpectedError() = runTest {
    every { ref.get() } throws terminated()

    assertEquals(AssociationResult.UnexpectedError, repository.getAssociation("club1"))
  }

  @Test
  fun getAssociation_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { ref.get() } returns TaskCompletionSource<DocumentSnapshot>().task
    var result: AssociationResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.getAssociation("club1") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertNull(result)
  }

  // ---- createAssociation ----

  @Test
  fun createAssociation_writesExactlyTheRulesFieldsToAssociationsUid() = runTest {
    transactionsSee(snapshot(null))
    val written = slot<Map<String, Any>>()
    every { transaction.set(ref, capture(written)) } returns transaction

    val result = repository.createAssociation(association)

    assertEquals(CreateAssociationResult.Created, result)
    verify { associations.document("club1") }
    assertEquals(
        mapOf(
            "name" to "Test Club",
            "type" to "CLUB",
            "about" to "A fictional club for tests.",
            "website" to "https://club.example.org",
            "createdAt" to FieldValue.serverTimestamp(),
        ),
        written.captured,
    )
  }

  @Test
  fun createAssociation_withoutAWebsite_leavesTheFieldOut() = runTest {
    transactionsSee(snapshot(null))
    val written = slot<Map<String, Any>>()
    every { transaction.set(ref, capture(written)) } returns transaction

    repository.createAssociation(association.copy(website = null))

    assertEquals(setOf("name", "type", "about", "createdAt"), written.captured.keys)
  }

  @Test
  fun createAssociation_ignoresAClientCreatedAt() = runTest {
    transactionsSee(snapshot(null))
    val written = slot<Map<String, Any>>()
    every { transaction.set(ref, capture(written)) } returns transaction

    repository.createAssociation(association.copy(createdAt = Instant.ofEpochSecond(1)))

    assertEquals(FieldValue.serverTimestamp(), written.captured["createdAt"])
  }

  @Test
  fun createAssociation_existingAssociation_returnsAlreadyExistsAndWritesNothing() = runTest {
    transactionsSee(snapshot(storedFields))

    assertEquals(CreateAssociationResult.AlreadyExists, repository.createAssociation(association))
    verify(exactly = 0) { transaction.set(any(), any()) }
  }

  @Test
  fun createAssociation_offline_returnsNetworkError() = runTest {
    transactionsFail(offline())

    assertEquals(CreateAssociationResult.NetworkError, repository.createAssociation(association))
  }

  @Test
  fun createAssociation_deniedByTheRules_returnsUnexpectedError() = runTest {
    transactionsFail(denied())

    assertEquals(CreateAssociationResult.UnexpectedError, repository.createAssociation(association))
  }

  @Test
  fun createAssociation_cancelledTask_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns Tasks.forCanceled()

    assertEquals(CreateAssociationResult.UnexpectedError, repository.createAssociation(association))
  }

  @Test
  fun createAssociation_unexpectedException_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } throws terminated()

    assertEquals(CreateAssociationResult.UnexpectedError, repository.createAssociation(association))
  }

  @Test
  fun createAssociation_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns
        TaskCompletionSource<Boolean>().task
    var result: CreateAssociationResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) {
          result = repository.createAssociation(association)
        }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertNull(result)
  }

  // ---- updateAssociation ----

  @Test
  fun updateAssociation_writesOnlyTheEditableFields() = runTest {
    transactionsSee(snapshot(storedFields))
    val written = slot<Map<String, Any>>()
    every { transaction.update(ref, capture(written)) } returns transaction

    val result =
        repository.updateAssociation(
            association.copy(
                name = "Renamed Club",
                type = AssociationType.COMMISSION,
                about = "New about.",
                website = "https://new.example.org",
                createdAt = Instant.ofEpochSecond(1),
            )
        )

    assertEquals(UpdateAssociationResult.Updated, result)
    assertEquals(
        mapOf(
            "name" to "Renamed Club",
            "type" to "COMMISSION",
            "about" to "New about.",
            "website" to "https://new.example.org",
        ),
        written.captured,
    )
  }

  @Test
  fun updateAssociation_withoutAWebsite_deletesTheStoredOne() = runTest {
    transactionsSee(snapshot(storedFields))
    val written = slot<Map<String, Any>>()
    every { transaction.update(ref, capture(written)) } returns transaction

    repository.updateAssociation(association.copy(website = null))

    assertEquals(FieldValue.delete(), written.captured["website"])
  }

  @Test
  fun updateAssociation_missingAssociation_returnsNotFoundAndWritesNothing() = runTest {
    transactionsSee(snapshot(null))

    assertEquals(UpdateAssociationResult.NotFound, repository.updateAssociation(association))
    verify(exactly = 0) { transaction.update(any<DocumentReference>(), any<Map<String, Any>>()) }
  }

  @Test
  fun updateAssociation_offline_returnsNetworkError() = runTest {
    transactionsFail(offline())

    assertEquals(UpdateAssociationResult.NetworkError, repository.updateAssociation(association))
  }

  @Test
  fun updateAssociation_deniedByTheRules_returnsUnexpectedError() = runTest {
    // For example a new name for an association an admin has already verified.
    transactionsFail(denied())

    assertEquals(UpdateAssociationResult.UnexpectedError, repository.updateAssociation(association))
  }

  @Test
  fun updateAssociation_cancelledTask_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns Tasks.forCanceled()

    assertEquals(UpdateAssociationResult.UnexpectedError, repository.updateAssociation(association))
  }

  @Test
  fun updateAssociation_unexpectedFailureOfTheTask_returnsUnexpectedError() = runTest {
    transactionsFail(terminated())

    assertEquals(UpdateAssociationResult.UnexpectedError, repository.updateAssociation(association))
  }

  @Test
  fun updateAssociation_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns
        TaskCompletionSource<Boolean>().task
    var result: UpdateAssociationResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) {
          result = repository.updateAssociation(association)
        }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertNull(result)
  }
}
