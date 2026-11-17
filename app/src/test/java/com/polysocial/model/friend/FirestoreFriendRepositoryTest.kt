// Contributors: Claude Opus 5.5 (wrote these tests, #357; a request changed between the read and
// the commit, from Lucas Pinto Ricardo's review); Konstantinos Perdikeas (directed, reviewed and
// owns them).
package com.polysocial.model.friend

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreFriendRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val friendships = mockk<CollectionReference>()
  private val pair = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>(relaxed = true)
  private val written = slot<Map<String, Any>>()
  private val options = slot<TransactionOptions>()

  private val auth = FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository = FirestoreFriendRepository(db, auth, network)

  private val created = Instant.parse("2026-11-16T10:00:00Z")
  private val later = Instant.parse("2026-11-16T11:00:00Z")

  /** A request bob sent to alice, as a screen shows it. */
  private val fromBob =
      Friendship(
          id = "alice~bob",
          uids = listOf("alice", "bob"),
          requestedBy = "bob",
          status = FriendshipStatus.PENDING,
          createdAt = created,
          acceptedAt = null,
      )

  private fun data(friendship: Friendship): Map<String, Any> = buildMap {
    put("uids", friendship.uids)
    put("requestedBy", friendship.requestedBy)
    put("status", friendship.status.name.lowercase())
    put("createdAt", Timestamp(friendship.createdAt))
    friendship.acceptedAt?.let { put("acceptedAt", Timestamp(it)) }
  }

  private fun snapshot(stored: Map<String, Any>?) =
      mockk<DocumentSnapshot>().also {
        every { it.exists() } returns (stored != null)
        every { it.data } returns stored
      }

  /**
   * What the transaction reads under `friendships/alice~bob`; null for a missing document. A read
   * from the server after the transaction finds the same, unless [stubServer] says otherwise.
   */
  private fun stubStored(stored: Map<String, Any>?) {
    every { transaction.get(pair) } returns snapshot(stored)
    stubServer(stored)
  }

  /** What a read of `friendships/alice~bob` from the server finds, after the transaction. */
  private fun stubServer(stored: Map<String, Any>?) {
    every { pair.get(Source.SERVER) } returns Tasks.forResult(snapshot(stored))
  }

  @Before
  fun stubFirestore() {
    every { db.collection(FRIENDSHIPS_COLLECTION) } returns friendships
    every { friendships.document("alice~bob") } returns pair
    stubStored(null)
    every { transaction.set(pair, capture(written)) } returns transaction
    every { transaction.update(pair, capture(written)) } returns transaction
    every { db.runTransaction(capture(options), any<Transaction.Function<Any>>()) } answers
        {
          Tasks.forResult(secondArg<Transaction.Function<Any>>().apply(transaction))
        }
  }

  private fun failed(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException("failed", code)

  /** Runs the transaction's reads and writes, then fails its commit with [code]. */
  private fun failTheCommitWith(code: FirebaseFirestoreException.Code) {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } answers
        {
          secondArg<Transaction.Function<Any>>().apply(transaction)
          Tasks.forException(failed(code))
        }
  }

  private fun verifyNoTransaction() =
      verify(exactly = 0) {
        db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>())
      }

  private fun verifyNothingWritten() =
      verify(exactly = 0) {
        transaction.set(any(), any())
        transaction.update(any(), any<Map<String, Any>>())
        transaction.delete(any())
      }

  // sendRequest

  @Test
  fun sendRequest_readsThePairThenWritesTheRequest() = runTest {
    assertEquals(SendFriendRequestResult.Sent, repository.sendRequest("bob"))

    assertEquals(newFriendRequestFirestoreMap(fromUid = "alice", toUid = "bob"), written.captured)
    verifyOrder {
      transaction.get(pair)
      transaction.set(pair, any())
    }
  }

  @Test
  fun sendRequest_toALowerUid_usesTheSamePairDocument() = runTest {
    auth.user = AuthUser("bob", "bob@epfl.ch", true)

    assertEquals(SendFriendRequestResult.Sent, repository.sendRequest("alice"))

    assertEquals(newFriendRequestFirestoreMap(fromUid = "bob", toUid = "alice"), written.captured)
  }

  @Test
  fun sendRequest_retriesTheTransaction_soASimultaneousRequestIsSeen() = runTest {
    repository.sendRequest("bob")

    assertTrue("maxAttempts was ${options.captured.maxAttempts}", options.captured.maxAttempts > 1)
  }

  @Test
  fun sendRequest_whenThePairAlreadyHasADocument_alreadyExistsAndWritesNothing() = runTest {
    stubStored(data(fromBob))

    assertEquals(SendFriendRequestResult.AlreadyExists, repository.sendRequest("bob"))

    verifyNothingWritten()
  }

  @Test
  fun sendRequest_toYourselfOrAnInvalidUid_isUnexpectedWithoutFirestore() = runTest {
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("alice"))
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("b~ob"))
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("bob/x"))
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest(""))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun sendRequest_nobodySignedIn_isUnexpectedWithoutATransaction() = runTest {
    auth.user = null

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("bob"))

    verifyNoTransaction()
  }

  @Test
  fun sendRequest_offline_isANetworkErrorWithoutStartingAWrite() = runTest {
    online = false

    assertEquals(SendFriendRequestResult.NetworkError, repository.sendRequest("bob"))

    verifyNoTransaction()
  }

  @Test
  fun sendRequest_anOwnOrInvalidUid_isUnexpectedEvenOffline() = runTest {
    online = false

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("alice"))
  }

  @Test
  fun sendRequest_losingTheConnectionDuringTheWrite_isANetworkError() = runTest {
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)

    assertEquals(SendFriendRequestResult.NetworkError, repository.sendRequest("bob"))
  }

  @Test
  fun sendRequest_aWriteTheRulesDeny_isUnexpected() = runTest {
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("bob"))
  }

  @Test
  fun sendRequest_anUnexpectedException_isUnexpected() = runTest {
    every { db.collection(FRIENDSHIPS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("bob"))
  }

  @Test
  fun sendRequest_aTaskCancelledWhileTheCallerRuns_isUnexpected() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        Tasks.forCanceled()

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("bob"))
  }

  @Test
  fun sendRequest_cancellingTheCaller_stillCancelsIt() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        TaskCompletionSource<Any>().task
    var result: SendFriendRequestResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.sendRequest("bob") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertNull(result)
  }

  // accept

  @Test
  fun accept_byTheRecipient_readsItAgainThenSetsAcceptedAtTheServerTime() = runTest {
    stubStored(data(fromBob))

    assertEquals(FriendshipActionResult.Done, repository.accept(fromBob))

    assertEquals(acceptanceFirestoreMap(), written.captured)
    verifyOrder {
      transaction.get(pair)
      transaction.update(pair, any<Map<String, Any>>())
    }
    assertTrue(options.captured.maxAttempts > 1)
  }

  @Test
  fun accept_ofARequestThatIsGone_isGoneAndWritesNothing() = runTest {
    assertEquals(FriendshipActionResult.Gone, repository.accept(fromBob))

    verifyNothingWritten()
  }

  @Test
  fun accept_ofARequestAcceptedOrSentAgainSinceItWasShown_isGone() = runTest {
    stubStored(data(fromBob.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = later)))
    assertEquals(FriendshipActionResult.Gone, repository.accept(fromBob))

    stubStored(data(fromBob.copy(createdAt = later)))
    assertEquals(FriendshipActionResult.Gone, repository.accept(fromBob))

    verifyNothingWritten()
  }

  @Test
  fun accept_byTheSender_isUnexpectedAndWritesNothing() = runTest {
    auth.user = AuthUser("bob", "bob@epfl.ch", true)
    stubStored(data(fromBob))

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(fromBob))

    verifyNothingWritten()
  }

  @Test
  fun accept_ofAnAcceptedFriendship_isUnexpectedAndWritesNothing() = runTest {
    val friends = fromBob.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = later)
    stubStored(data(friends))

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(friends))

    verifyNothingWritten()
  }

  @Test
  fun accept_ofAStaleRequestTheSenderShows_isGoneBeforeTheSenderCheck() = runTest {
    auth.user = AuthUser("bob", "bob@epfl.ch", true)

    assertEquals(FriendshipActionResult.Gone, repository.accept(fromBob))
  }

  @Test
  fun accept_ofAStoredDocumentThatCannotBeRead_isUnexpected() = runTest {
    stubStored(data(fromBob) + ("status" to "blocked"))

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(fromBob))

    verifyNothingWritten()
  }

  @Test
  fun accept_byAStudentOutsideThePair_isUnexpectedWithoutATransaction() = runTest {
    auth.user = AuthUser("carol", "carol@epfl.ch", true)

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(fromBob))

    verifyNoTransaction()
  }

  @Test
  fun accept_ofAnIdFriendshipIdWouldNotBuild_isUnexpectedWithoutFirestore() = runTest {
    for (id in listOf("alice~bob~", "bob~alice", "alice/x~bob", "alice")) {
      assertEquals(
          id,
          FriendshipActionResult.UnexpectedError,
          repository.accept(fromBob.copy(id = id)),
      )
    }

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun accept_nobodySignedIn_isUnexpectedWithoutATransaction() = runTest {
    auth.user = null

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(fromBob))

    verifyNoTransaction()
  }

  @Test
  fun accept_offline_isANetworkErrorBeforeThePairIsChecked() = runTest {
    online = false
    assertEquals(FriendshipActionResult.NetworkError, repository.accept(fromBob))

    auth.user = AuthUser("carol", "carol@epfl.ch", true)
    assertEquals(FriendshipActionResult.NetworkError, repository.accept(fromBob))

    verifyNoTransaction()
  }

  @Test
  fun accept_losingTheConnectionOrADeniedWrite_isANetworkOrUnexpectedError() = runTest {
    stubStored(data(fromBob))
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)
    assertEquals(FriendshipActionResult.NetworkError, repository.accept(fromBob))

    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(fromBob))
  }

  @Test
  fun accept_deniedBecauseTheRequestChangedAfterTheRead_isGone() = runTest {
    // The transaction reads bob's request as shown; before it commits, bob cancels it, or sends it
    // again, or alice's other phone accepts it. The rules then deny the write, which Firestore
    // doesn't retry, so the repository reads the pair again from the server.
    stubStored(data(fromBob))
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    for (now in
        listOf(
            null,
            data(fromBob.copy(createdAt = later)),
            data(fromBob.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = later)),
        )) {
      stubServer(now)

      assertEquals("$now", FriendshipActionResult.Gone, repository.accept(fromBob))
    }
    verify(exactly = 3) { pair.get(Source.SERVER) }
  }

  @Test
  fun accept_deniedWhileTheRequestIsUnchanged_isUnexpected() = runTest {
    stubStored(data(fromBob))
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(fromBob))
    verify(exactly = 1) { pair.get(Source.SERVER) }
  }

  @Test
  fun accept_deniedThenTheServerCannotBeReached_isANetworkError() = runTest {
    stubStored(data(fromBob))
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    every { pair.get(Source.SERVER) } returns
        Tasks.forException(failed(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(FriendshipActionResult.NetworkError, repository.accept(fromBob))
  }

  @Test
  fun accept_otherFailures_areNotReadAgain() = runTest {
    stubStored(data(fromBob))
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)

    assertEquals(FriendshipActionResult.NetworkError, repository.accept(fromBob))
    verify(exactly = 0) { pair.get(Source.SERVER) }
  }

  @Test
  fun accept_anUnexpectedException_isUnexpected() = runTest {
    every { db.collection(FRIENDSHIPS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(fromBob))
  }

  // remove

  @Test
  fun remove_readsItAgainThenDeletesIt_forEitherStudent() = runTest {
    stubStored(data(fromBob))
    assertEquals(FriendshipActionResult.Done, repository.remove(fromBob))

    auth.user = AuthUser("bob", "bob@epfl.ch", true)
    assertEquals(FriendshipActionResult.Done, repository.remove(fromBob))

    verifyOrder {
      transaction.get(pair)
      transaction.delete(pair)
      transaction.get(pair)
      transaction.delete(pair)
    }
    assertTrue(options.captured.maxAttempts > 1)
  }

  @Test
  fun remove_anAcceptedFriendship_deletesIt() = runTest {
    val friends = fromBob.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = later)
    stubStored(data(friends))

    assertEquals(FriendshipActionResult.Done, repository.remove(friends))

    verify { transaction.delete(pair) }
  }

  @Test
  fun aStaleCancel_afterTheRequestWasAccepted_isGoneAndKeepsTheFriendship() = runTest {
    stubStored(data(fromBob.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = later)))

    assertEquals(FriendshipActionResult.Gone, repository.remove(fromBob))

    verifyNothingWritten()
  }

  @Test
  fun remove_ofARequestThatIsGoneOrSentAgain_isGone() = runTest {
    assertEquals(FriendshipActionResult.Gone, repository.remove(fromBob))

    stubStored(data(fromBob.copy(createdAt = later)))
    assertEquals(FriendshipActionResult.Gone, repository.remove(fromBob))

    verifyNothingWritten()
  }

  @Test
  fun remove_byAStudentOutsideThePairOrWithAnInvalidId_isUnexpectedWithoutATransaction() = runTest {
    assertEquals(
        FriendshipActionResult.UnexpectedError,
        repository.remove(fromBob.copy(id = "alice~bob~")),
    )
    auth.user = AuthUser("carol", "carol@epfl.ch", true)
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(fromBob))

    verifyNoTransaction()
  }

  @Test
  fun remove_nobodySignedInOrOffline_failsWithoutATransaction() = runTest {
    online = false
    assertEquals(FriendshipActionResult.NetworkError, repository.remove(fromBob))

    auth.user = null
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(fromBob))

    verifyNoTransaction()
  }

  @Test
  fun remove_losingTheConnectionOrADeniedWrite_isANetworkOrUnexpectedError() = runTest {
    stubStored(data(fromBob))
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)
    assertEquals(FriendshipActionResult.NetworkError, repository.remove(fromBob))

    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(fromBob))
  }

  @Test
  fun remove_deniedBecauseTheOtherStudentDeletedItAfterTheRead_isGone() = runTest {
    // A decline and a cancel at the same moment: the second delete is denied on a missing document.
    stubStored(data(fromBob))
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    stubServer(null)

    assertEquals(FriendshipActionResult.Gone, repository.remove(fromBob))
  }

  // observeFriendships

  private val mine = mockk<Query>()
  private val registration = mockk<ListenerRegistration>(relaxed = true)
  private val listener = slot<EventListener<QuerySnapshot>>()

  /** Stubs exactly the query the repository must build, so any other query fails the test. */
  private fun stubMyFriendshipsQuery(uid: String = "alice") {
    every { friendships.whereArrayContains(FRIENDSHIP_UIDS, uid) } returns mine
    every { mine.addSnapshotListener(MetadataChanges.INCLUDE, capture(listener)) } returns
        registration
  }

  private fun stored(id: String, data: Map<String, Any>) =
      mockk<DocumentSnapshot>().also {
        every { it.id } returns id
        every { it.data } returns data
      }

  private fun friendshipsSnapshot(vararg documents: DocumentSnapshot, fromCache: Boolean = false) =
      mockk<QuerySnapshot>().also {
        every { it.documents } returns documents.toList()
        every { it.metadata } returns
            mockk<SnapshotMetadata>().also { m -> every { m.isFromCache } returns fromCache }
      }

  @Test
  fun observe_emitsTheStudentsFriendshipsOnEveryChange_skippingUnreadableOnes() = runTest {
    stubMyFriendshipsQuery()
    val withCarol =
        fromBob.copy(id = "alice~carol", uids = listOf("alice", "carol"), requestedBy = "alice")
    val results = mutableListOf<FriendshipsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeFriendships().collect { results += it }
        }

    listener.captured.onEvent(
        friendshipsSnapshot(
            stored("alice~bob", data(fromBob)),
            stored("alice~dan", data(fromBob) + ("status" to "blocked")),
            stored("alice~carol", data(withCarol)),
            fromCache = true,
        ),
        null,
    )
    listener.captured.onEvent(friendshipsSnapshot(stored("alice~carol", data(withCarol))), null)
    collecting.cancel()

    assertEquals(
        listOf(
            FriendshipsResult.Success(listOf(fromBob, withCarol), fromCache = true),
            FriendshipsResult.Success(listOf(withCarol), fromCache = false),
        ),
        results,
    )
    verify { registration.remove() }
  }

  @Test
  fun observe_readsTheUserWhenCollectionStarts() = runTest {
    auth.user = null
    val flow = repository.observeFriendships()
    auth.user = AuthUser("bob", "bob@epfl.ch", true)
    stubMyFriendshipsQuery(uid = "bob")
    val results = async(UnconfinedTestDispatcher(testScheduler)) { flow.toList() }

    listener.captured.onEvent(null, failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(listOf(FriendshipsResult.Error), results.await())
    verify { friendships.whereArrayContains(FRIENDSHIP_UIDS, "bob") }
  }

  @Test
  fun observe_nobodySignedIn_isAnErrorWithoutAQuery() = runTest {
    auth.user = null

    assertEquals(listOf(FriendshipsResult.Error), repository.observeFriendships().toList())

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun observe_aFailedQueryOrANullSnapshot_isAnErrorAndEndsTheFlow() = runTest {
    stubMyFriendshipsQuery()
    val denied =
        async(UnconfinedTestDispatcher(testScheduler)) { repository.observeFriendships().toList() }
    listener.captured.onEvent(null, failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))
    assertEquals(listOf(FriendshipsResult.Error), denied.await())

    val empty =
        async(UnconfinedTestDispatcher(testScheduler)) { repository.observeFriendships().toList() }
    listener.captured.onEvent(null, null)
    assertEquals(listOf(FriendshipsResult.Error), empty.await())
  }

  @Test
  fun observe_aSnapshotThatCannotBeRead_isAnErrorAndEndsTheFlow() = runTest {
    stubMyFriendshipsQuery()
    val unreadable =
        mockk<QuerySnapshot>().also {
          every { it.documents } throws IllegalStateException("terminated")
        }
    val results =
        async(UnconfinedTestDispatcher(testScheduler)) { repository.observeFriendships().toList() }

    listener.captured.onEvent(unreadable, null)

    assertEquals(listOf(FriendshipsResult.Error), results.await())
  }

  @Test
  fun observe_anUnexpectedExceptionWhileStartingToListen_isAnErrorAndEnds() = runTest {
    every { db.collection(FRIENDSHIPS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(listOf(FriendshipsResult.Error), repository.observeFriendships().toList())
  }
}
