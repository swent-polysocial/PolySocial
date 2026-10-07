// Contributors: Claude (wrote these tests).
package com.polysocial.model.event

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirestoreEventRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val collection = mockk<CollectionReference>()
  private val document = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val written = slot<Any>()

  private val auth = FakeAuthRepository(AuthUser("creator", "creator@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository =
      FirestoreEventRepository(db, auth, network, Clock.fixed(TEST_NOW, ZoneOffset.UTC))

  @Before
  fun stubFirestore() {
    every { db.collection(EVENTS_COLLECTION) } returns collection
    every { collection.document() } returns document
    every { document.id } returns "event-42"
    every { transaction.set(document, capture(written)) } returns transaction
    every { db.runTransaction(any<Transaction.Function<Transaction>>()) } answers
        {
          Tasks.forResult(firstArg<Transaction.Function<Transaction>>().apply(transaction))
        }
  }

  private fun failWith(code: FirebaseFirestoreException.Code) {
    every { db.runTransaction(any<Transaction.Function<Transaction>>()) } returns
        Tasks.forException(FirebaseFirestoreException("failed", code))
  }

  private fun writtenFields() = written.captured as Map<*, *>

  @Test
  fun aValidEvent_isWrittenAndGetsTheNewDocumentId() = runTest {
    assertEquals(CreateEventResult.Created("event-42"), repository.createEvent(validEvent()))

    assertEquals(validEvent().withCreator("creator", false).toFirestoreMap(), writtenFields())
  }

  @Test
  fun theCreator_isTheOnlyOrganizerAndReaderAndGetsNoBadge() = runTest {
    val claimed =
        validEvent()
            .copy(
                createdBy = "someone-else",
                organizerIds = listOf("someone-else"),
                allowedUids = listOf("intruder"),
                isAssociationEvent = true,
            )

    repository.createEvent(claimed)

    val fields = writtenFields()
    assertEquals("creator", fields["createdBy"])
    assertEquals(listOf("creator"), fields["organizerIds"])
    assertEquals(listOf("creator"), fields["allowedUids"])
    assertEquals(false, fields["isAssociationEvent"])
  }

  @Test
  fun anInvalidEvent_isRejectedBeforeFirestore() = runTest {
    val result =
        repository.createEvent(validEvent(title = " ", startTime = TEST_NOW.minusSeconds(3600)))

    assertEquals(
        CreateEventResult.Invalid(
            listOf(EventValidationError.BLANK_TITLE, EventValidationError.START_IN_PAST)
        ),
        result,
    )
    verify { db wasNot Called }
  }

  @Test
  fun nobodySignedIn_writesNothing() = runTest {
    auth.user = null

    assertEquals(CreateEventResult.NotSignedIn, repository.createEvent(validEvent()))
    verify { db wasNot Called }
  }

  @Test
  fun offline_returnsANetworkErrorWithoutStartingAWrite() = runTest {
    online = false

    assertEquals(CreateEventResult.NetworkError, repository.createEvent(validEvent()))
    verify { db wasNot Called }
  }

  @Test
  fun losingTheConnectionDuringTheWrite_isANetworkError() = runTest {
    failWith(FirebaseFirestoreException.Code.UNAVAILABLE)

    assertEquals(CreateEventResult.NetworkError, repository.createEvent(validEvent()))
  }

  @Test
  fun aWriteTheRulesDeny_isAnUnexpectedError() = runTest {
    failWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(CreateEventResult.UnexpectedError, repository.createEvent(validEvent()))
  }
}
