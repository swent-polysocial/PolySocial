// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #232).
package com.polysocial.model.group

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
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Transaction
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreGroupRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val events = mockk<CollectionReference>()
  private val event = mockk<DocumentReference>()
  private val groups = mockk<CollectionReference>()
  private val group = mockk<DocumentReference>()
  private val newGroup = mockk<DocumentReference>()
  private val markers = mockk<CollectionReference>()
  private val marker = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()

  private val auth = FakeAuthRepository(AuthUser(STUDENT, "student@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository = FirestoreGroupRepository(db, auth, network)

  /** What the transaction wrote, in order, as (operation, document, fields). */
  private val writes = mutableListOf<Triple<String, DocumentReference, Map<String, Any>?>>()

  @Before
  fun stubFirestore() {
    every { db.collection(EVENTS_COLLECTION) } returns events
    every { events.document(EVENT) } returns event
    every { event.collection(GROUPS_COLLECTION) } returns groups
    every { event.collection(GROUP_MEMBERS_COLLECTION) } returns markers
    every { groups.document(GROUP) } returns group
    every { groups.document() } returns newGroup
    every { newGroup.id } returns "new-group"
    every { markers.document(STUDENT) } returns marker
    stubStored(eventExists = true, group = null, hasMarker = false)
    every { transaction.set(any<DocumentReference>(), any<Map<String, Any>>()) } answers
        {
          writes += Triple("set", firstArg(), secondArg())
          transaction
        }
    every { transaction.update(any<DocumentReference>(), any<Map<String, Any>>()) } answers
        {
          writes += Triple("update", firstArg(), secondArg())
          transaction
        }
    every { transaction.delete(any()) } answers
        {
          writes += Triple("delete", firstArg(), null)
          transaction
        }
    every { db.runTransaction(any<Transaction.Function<Any>>()) } answers
        {
          Tasks.forResult(firstArg<Transaction.Function<Any>>().apply(transaction))
        }
  }

  private fun snapshot(data: Map<String, Any?>?) =
      mockk<DocumentSnapshot>().also {
        every { it.exists() } returns (data != null)
        every { it.data } returns data
      }

  private fun stubStored(eventExists: Boolean, group: Map<String, Any?>?, hasMarker: Boolean) {
    every { transaction.get(event) } returns snapshot(if (eventExists) mapOf() else null)
    every { transaction.get(this@FirestoreGroupRepositoryTest.group) } returns snapshot(group)
    every { transaction.get(marker) } returns
        snapshot(if (hasMarker) mapOf(MARKER_GROUP_ID to "another-group") else null)
  }

  private fun storedGroup(vararg members: String, capacity: Long = 4L) =
      mapOf(
          "capacity" to capacity,
          "memberUids" to members.toList(),
          "createdBy" to members.first(),
          "createdAt" to Timestamp(1_900_000_000L, 0),
      )

  private fun failed(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException("failed", code)

  /** Fails the transaction with [failure] before it reads anything, like a denied first read. */
  private fun failBeforeReading(failure: Exception) {
    every { db.runTransaction(any<Transaction.Function<Any>>()) } returns
        Tasks.forException(failure)
  }

  /** Runs the transaction's reads and writes, then fails its commit with [code]. */
  private fun failTheCommitWith(code: FirebaseFirestoreException.Code) {
    every { db.runTransaction(any<Transaction.Function<Any>>()) } answers
        {
          firstArg<Transaction.Function<Any>>().apply(transaction)
          Tasks.forException(failed(code))
        }
  }

  private fun verifyNoTransaction() {
    verify(exactly = 0) { db.runTransaction(any<Transaction.Function<Any>>()) }
  }

  // createGroup

  @Test
  fun createGroup_writesTheGroupAndTheMarkerTogether() = runTest {
    assertEquals(GroupWriteResult.Success("new-group"), repository.createGroup(EVENT))

    assertEquals(
        listOf(
            Triple("set", newGroup, newGroupFirestoreMap(STUDENT)),
            Triple("set", marker, mapOf(MARKER_GROUP_ID to "new-group")),
        ),
        writes,
    )
  }

  @Test
  fun createGroup_aMissingEvent_isNotFoundAndWritesNothing() = runTest {
    stubStored(eventExists = false, group = null, hasMarker = false)

    assertEquals(GroupWriteResult.NotFound, repository.createGroup(EVENT))

    assertEquals(emptyList<Any>(), writes)
  }

  @Test
  fun createGroup_whileInAGroupOfTheEvent_isAlreadyInGroupAndWritesNothing() = runTest {
    stubStored(eventExists = true, group = null, hasMarker = true)

    assertEquals(GroupWriteResult.AlreadyInGroup, repository.createGroup(EVENT))

    assertEquals(emptyList<Any>(), writes)
  }

  @Test
  fun createGroup_anEventTheRulesDontLetTheStudentRead_isNotFoundWithoutRetrying() = runTest {
    failBeforeReading(failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(GroupWriteResult.NotFound, repository.createGroup(EVENT))

    verify(exactly = 1) { db.runTransaction(any<Transaction.Function<Any>>()) }
  }

  @Test
  fun writes_aWriteTheRulesKeepDenyingAfterTheReads_isAnUnexpectedErrorAfterThreeAttempts() =
      runTest {
        failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        assertEquals(GroupWriteResult.UnexpectedError, repository.createGroup(EVENT))

        verify(exactly = 3) { db.runTransaction(any<Transaction.Function<Any>>()) }
      }

  @Test
  fun joinGroup_losingTheLastPlaceToAnotherStudent_readsAgainAndIsFull() = runTest {
    stubStored(eventExists = true, group = storedGroup("a", "b", "c"), hasMarker = false)
    var attempts = 0
    every { db.runTransaction(any<Transaction.Function<Any>>()) } answers
        {
          attempts++
          val result = firstArg<Transaction.Function<Any>>().apply(transaction)
          if (attempts == 1) {
            // Another student took the last place before this commit: the rules deny it.
            stubStored(
                eventExists = true,
                group = storedGroup("a", "b", "c", "d"),
                hasMarker = false,
            )
            Tasks.forException(failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))
          } else Tasks.forResult(result)
        }

    assertEquals(GroupWriteResult.Full, repository.joinGroup(EVENT, GROUP))

    assertEquals(2, attempts)
  }

  @Test
  fun leaveGroup_whileAnotherMemberLeaves_readsAgainAndRemovesOnlyTheStudent() = runTest {
    stubStored(eventExists = true, group = storedGroup("a", STUDENT, "b"), hasMarker = true)
    var attempts = 0
    every { db.runTransaction(any<Transaction.Function<Any>>()) } answers
        {
          attempts++
          writes.clear()
          val result = firstArg<Transaction.Function<Any>>().apply(transaction)
          if (attempts == 1) {
            stubStored(eventExists = true, group = storedGroup("a", STUDENT), hasMarker = true)
            Tasks.forException(failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))
          } else Tasks.forResult(result)
        }

    assertEquals(GroupWriteResult.Success(GROUP), repository.leaveGroup(EVENT, GROUP))

    assertEquals(
        listOf(
            Triple("update", group, mapOf(MEMBER_UIDS to listOf("a"))),
            Triple("delete", marker, null),
        ),
        writes,
    )
  }

  @Test
  fun writes_idsThatCannotBeDocumentIds_areNotFoundWithoutFirestore() = runTest {
    assertEquals(GroupWriteResult.NotFound, repository.createGroup("events/other"))
    assertEquals(GroupWriteResult.NotFound, repository.joinGroup(EVENT, " "))
    assertEquals(GroupWriteResult.NotFound, repository.leaveGroup("", GROUP))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun writes_nobodySignedIn_areUnexpectedErrorsWithoutATransaction() = runTest {
    auth.user = null

    assertEquals(GroupWriteResult.UnexpectedError, repository.createGroup(EVENT))
    assertEquals(GroupWriteResult.UnexpectedError, repository.joinGroup(EVENT, GROUP))
    assertEquals(GroupWriteResult.UnexpectedError, repository.leaveGroup(EVENT, GROUP))

    verifyNoTransaction()
  }

  @Test
  fun writes_offline_areNetworkErrorsWithoutStartingAWrite() = runTest {
    online = false

    assertEquals(GroupWriteResult.NetworkError, repository.createGroup(EVENT))
    assertEquals(GroupWriteResult.NetworkError, repository.joinGroup(EVENT, GROUP))
    assertEquals(GroupWriteResult.NetworkError, repository.leaveGroup(EVENT, GROUP))

    verifyNoTransaction()
  }

  @Test
  fun writes_losingTheConnectionDuringTheCommit_isANetworkError() = runTest {
    for (code in
        listOf(
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        )) {
      failTheCommitWith(code)

      assertEquals(code.name, GroupWriteResult.NetworkError, repository.createGroup(EVENT))
    }
  }

  @Test
  fun writes_aDocumentDeletedBeforeTheCommit_isNotFound() = runTest {
    stubStored(eventExists = true, group = storedGroup("creator"), hasMarker = false)
    failTheCommitWith(FirebaseFirestoreException.Code.NOT_FOUND)

    assertEquals(GroupWriteResult.NotFound, repository.joinGroup(EVENT, GROUP))
  }

  @Test
  fun writes_anotherFirestoreFailure_isAnUnexpectedError() = runTest {
    failBeforeReading(failed(FirebaseFirestoreException.Code.ABORTED))

    assertEquals(GroupWriteResult.UnexpectedError, repository.joinGroup(EVENT, GROUP))
  }

  @Test
  fun writes_anUnexpectedException_isAnUnexpectedError() = runTest {
    every { db.collection(EVENTS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(GroupWriteResult.UnexpectedError, repository.leaveGroup(EVENT, GROUP))
  }

  @Test
  fun writes_aTaskCancelledWhileTheCallerRuns_isAnUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Any>>()) } returns Tasks.forCanceled()

    assertEquals(GroupWriteResult.UnexpectedError, repository.createGroup(EVENT))
  }

  @Test
  fun writes_cancellingTheCaller_stillCancelsIt() = runTest {
    every { db.runTransaction(any<Transaction.Function<Any>>()) } returns
        TaskCompletionSource<Any>().task
    var result: GroupWriteResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.joinGroup(EVENT, GROUP) }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  // joinGroup

  @Test
  fun joinGroup_appendsTheStudentAndCreatesTheMarker() = runTest {
    stubStored(eventExists = true, group = storedGroup("creator", "other"), hasMarker = false)

    assertEquals(GroupWriteResult.Success(GROUP), repository.joinGroup(EVENT, GROUP))

    assertEquals(
        listOf(
            Triple("update", group, mapOf(MEMBER_UIDS to listOf("creator", "other", STUDENT))),
            Triple("set", marker, mapOf(MARKER_GROUP_ID to GROUP)),
        ),
        writes,
    )
  }

  @Test
  fun joinGroup_theLastPlace_isTaken() = runTest {
    stubStored(eventExists = true, group = storedGroup("a", "b", "c"), hasMarker = false)

    assertEquals(GroupWriteResult.Success(GROUP), repository.joinGroup(EVENT, GROUP))
    assertEquals(2, writes.size)
  }

  @Test
  fun joinGroup_aFullGroup_isFullAndWritesNothing() = runTest {
    stubStored(eventExists = true, group = storedGroup("a", "b", "c", "d"), hasMarker = false)

    assertEquals(GroupWriteResult.Full, repository.joinGroup(EVENT, GROUP))

    assertEquals(emptyList<Any>(), writes)
  }

  @Test
  fun joinGroup_checksTheStoredCapacity() = runTest {
    stubStored(eventExists = true, group = storedGroup("a", "b", capacity = 2L), hasMarker = false)

    assertEquals(GroupWriteResult.Full, repository.joinGroup(EVENT, GROUP))
  }

  @Test
  fun joinGroup_twice_isASuccessWithoutAWrite() = runTest {
    stubStored(eventExists = true, group = storedGroup("creator", STUDENT), hasMarker = true)

    assertEquals(GroupWriteResult.Success(GROUP), repository.joinGroup(EVENT, GROUP))

    assertEquals(emptyList<Any>(), writes)
  }

  @Test
  fun joinGroup_whileInAnotherGroupOfTheEvent_isAlreadyInGroup() = runTest {
    stubStored(eventExists = true, group = storedGroup("creator", "a", "b", "c"), hasMarker = true)

    assertEquals(GroupWriteResult.AlreadyInGroup, repository.joinGroup(EVENT, GROUP))

    assertEquals(emptyList<Any>(), writes)
  }

  @Test
  fun joinGroup_aMissingOrMalformedGroup_isNotFound() = runTest {
    for (stored in listOf(null, storedGroup("creator") - "memberUids")) {
      stubStored(eventExists = true, group = stored, hasMarker = false)

      assertEquals(GroupWriteResult.NotFound, repository.joinGroup(EVENT, GROUP))
    }
    assertEquals(emptyList<Any>(), writes)
  }

  @Test
  fun joinGroup_aGroupTheRulesDontLetTheStudentRead_isNotFound() = runTest {
    failBeforeReading(failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(GroupWriteResult.NotFound, repository.joinGroup(EVENT, GROUP))
  }

  // leaveGroup

  @Test
  fun leaveGroup_removesOnlyTheStudentAndDeletesTheMarker() = runTest {
    stubStored(eventExists = true, group = storedGroup("creator", STUDENT, "b"), hasMarker = true)

    assertEquals(GroupWriteResult.Success(GROUP), repository.leaveGroup(EVENT, GROUP))

    assertEquals(
        listOf(
            Triple("update", group, mapOf(MEMBER_UIDS to listOf("creator", "b"))),
            Triple("delete", marker, null),
        ),
        writes,
    )
  }

  @Test
  fun leaveGroup_theLastMember_deletesTheGroupAndTheMarker() = runTest {
    stubStored(eventExists = true, group = storedGroup(STUDENT), hasMarker = true)

    assertEquals(GroupWriteResult.Success(GROUP), repository.leaveGroup(EVENT, GROUP))

    assertEquals(listOf(Triple("delete", group, null), Triple("delete", marker, null)), writes)
  }

  @Test
  fun leaveGroup_withoutAMarker_deletesNoMarker() = runTest {
    stubStored(eventExists = true, group = storedGroup("creator", STUDENT), hasMarker = false)

    assertEquals(GroupWriteResult.Success(GROUP), repository.leaveGroup(EVENT, GROUP))

    assertEquals(listOf(Triple("update", group, mapOf(MEMBER_UIDS to listOf("creator")))), writes)
  }

  @Test
  fun leaveGroup_aGroupTheStudentIsNotIn_isASuccessWithoutAWrite() = runTest {
    stubStored(eventExists = true, group = storedGroup("creator"), hasMarker = false)

    assertEquals(GroupWriteResult.Success(GROUP), repository.leaveGroup(EVENT, GROUP))

    assertEquals(emptyList<Any>(), writes)
  }

  @Test
  fun leaveGroup_aMissingGroup_isNotFound() = runTest {
    assertEquals(GroupWriteResult.NotFound, repository.leaveGroup(EVENT, GROUP))

    assertEquals(emptyList<Any>(), writes)
  }

  // observeGroups and observeMyGroup

  private val mine = mockk<Query>()
  private val registration = mockk<ListenerRegistration>(relaxed = true)
  private val listener = slot<EventListener<QuerySnapshot>>()

  private fun stubGroupsListener() {
    every { groups.addSnapshotListener(MetadataChanges.INCLUDE, capture(listener)) } returns
        registration
  }

  /** Stubs exactly the query observeMyGroup must build, so any other query fails the test. */
  private fun stubMyGroupListener() {
    every { groups.whereArrayContains(MEMBER_UIDS, STUDENT) } returns mine
    every { mine.addSnapshotListener(MetadataChanges.INCLUDE, capture(listener)) } returns
        registration
  }

  private fun document(id: String, data: Map<String, Any?>) =
      mockk<QueryDocumentSnapshot>().also {
        every { it.id } returns id
        every { it.data } returns data
      }

  private fun groupsSnapshot(
      vararg documents: QueryDocumentSnapshot,
      fromCache: Boolean = false,
  ) =
      mockk<QuerySnapshot>().also {
        every { it.documents } returns documents.toList()
        every { it.metadata } returns
            mockk<SnapshotMetadata>().also { m -> every { m.isFromCache } returns fromCache }
      }

  private fun expectedGroup(id: String, createdAt: Instant?, vararg members: String) =
      Group(id, EVENT, 4, members.toList(), members.first(), createdAt)

  private fun <T> TestScope.collect(flow: kotlinx.coroutines.flow.Flow<T>) =
      async(UnconfinedTestDispatcher(testScheduler)) { flow.toList() }

  @Test
  fun observeGroups_emitsLoadingThenEveryReadableGroupOldestFirst() = runTest {
    stubGroupsListener()
    val results = mutableListOf<GroupsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeGroups(EVENT).collect { results += it }
        }
    val newer = storedGroup("b") + ("createdAt" to Timestamp(1_900_000_100L, 0))
    val pending = storedGroup("c") + ("createdAt" to null)

    listener.captured.onEvent(
        groupsSnapshot(
            document("pending", pending),
            document("newer", newer),
            document("older", storedGroup("a")),
            document("broken", mapOf("capacity" to "four")),
            fromCache = true,
        ),
        null,
    )
    listener.captured.onEvent(groupsSnapshot(), null)
    collecting.cancel()

    assertEquals(
        listOf(
            GroupsResult.Loading,
            GroupsResult.Success(
                listOf(
                    expectedGroup("older", Instant.ofEpochSecond(1_900_000_000L), "a"),
                    expectedGroup("newer", Instant.ofEpochSecond(1_900_000_100L), "b"),
                    expectedGroup("pending", null, "c"),
                ),
                fromCache = true,
            ),
            GroupsResult.Success(emptyList(), fromCache = false),
        ),
        results,
    )
    verify { registration.remove() }
  }

  @Test
  fun observeGroups_anUnreachableServer_isANetworkErrorAndEndsTheFlow() = runTest {
    stubGroupsListener()
    val results = collect(repository.observeGroups(EVENT))

    listener.captured.onEvent(null, failed(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(listOf(GroupsResult.Loading, GroupsResult.NetworkError), results.await())
  }

  @Test
  fun observeGroups_aDeniedListener_isAnUnexpectedErrorAndEndsTheFlow() = runTest {
    stubGroupsListener()
    val results = collect(repository.observeGroups(EVENT))

    listener.captured.onEvent(null, failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(listOf(GroupsResult.Loading, GroupsResult.UnexpectedError), results.await())
  }

  @Test
  fun observeGroups_aNullSnapshotWithoutAnError_isAnUnexpectedError() = runTest {
    stubGroupsListener()
    val results = collect(repository.observeGroups(EVENT))

    listener.captured.onEvent(null, null)

    assertEquals(listOf(GroupsResult.Loading, GroupsResult.UnexpectedError), results.await())
  }

  @Test
  fun observeGroups_aSnapshotThatCannotBeRead_isAnUnexpectedError() = runTest {
    stubGroupsListener()
    val unreadable =
        mockk<QuerySnapshot>().also {
          every { it.documents } throws IllegalStateException("terminated")
        }
    val results = collect(repository.observeGroups(EVENT))

    listener.captured.onEvent(unreadable, null)

    assertEquals(listOf(GroupsResult.Loading, GroupsResult.UnexpectedError), results.await())
  }

  @Test
  fun observeGroups_anUnexpectedExceptionWhileStartingToListen_isAnUnexpectedError() = runTest {
    every { db.collection(EVENTS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(
        listOf(GroupsResult.Loading, GroupsResult.UnexpectedError),
        repository.observeGroups(EVENT).toList(),
    )
  }

  @Test
  fun observe_anIdThatCannotBeADocumentId_isAnUnexpectedErrorWithoutFirestore() = runTest {
    assertEquals(
        listOf(GroupsResult.Loading, GroupsResult.UnexpectedError),
        repository.observeGroups("events/other").toList(),
    )
    assertEquals(
        listOf(MyGroupResult.Loading, MyGroupResult.UnexpectedError),
        repository.observeMyGroup(" ").toList(),
    )

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun observeMyGroup_queriesOnlyTheStudentsGroupAndEmitsIt() = runTest {
    stubMyGroupListener()
    val results = mutableListOf<MyGroupResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMyGroup(EVENT).collect { results += it }
        }

    listener.captured.onEvent(groupsSnapshot(document(GROUP, storedGroup("a", STUDENT))), null)
    listener.captured.onEvent(groupsSnapshot(fromCache = true), null)
    collecting.cancel()

    assertEquals(
        listOf(
            MyGroupResult.Loading,
            MyGroupResult.Success(
                expectedGroup(GROUP, Instant.ofEpochSecond(1_900_000_000L), "a", STUDENT),
                fromCache = false,
            ),
            MyGroupResult.Success(null, fromCache = true),
        ),
        results,
    )
    verify { registration.remove() }
  }

  @Test
  fun observeMyGroup_failures_endTheFlowWithTheirOwnError() = runTest {
    stubMyGroupListener()
    val offline = collect(repository.observeMyGroup(EVENT))
    listener.captured.onEvent(null, failed(FirebaseFirestoreException.Code.UNAVAILABLE))
    val denied = collect(repository.observeMyGroup(EVENT))
    listener.captured.onEvent(null, failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(listOf(MyGroupResult.Loading, MyGroupResult.NetworkError), offline.await())
    assertEquals(listOf(MyGroupResult.Loading, MyGroupResult.UnexpectedError), denied.await())
  }

  @Test
  fun observeMyGroup_nobodySignedIn_isAnUnexpectedErrorWithoutAQuery() = runTest {
    auth.user = null

    assertEquals(
        listOf(MyGroupResult.Loading, MyGroupResult.UnexpectedError),
        repository.observeMyGroup(EVENT).toList(),
    )

    verify(exactly = 0) { groups.whereArrayContains(any<String>(), any()) }
  }

  private companion object {
    const val STUDENT = "student"
    const val EVENT = "event-1"
    const val GROUP = "group-1"
  }
}
