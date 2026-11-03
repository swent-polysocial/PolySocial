// Contributors: Claude (wrote these tests, written test-first, #229; unreadable documents in a
// batch, tests added in the test audit, #229; several profiles read one document each, in
// parallel, one failing read failing them all, after review, #229; unknown interest codes left
// out, after review, #230).
package com.polysocial.model.user

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestorePublicProfileRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val profiles = mockk<CollectionReference>()

  /** One mocked `publicProfiles/{uid}` reference per UID, created on first use. */
  private val refs = mutableMapOf<String, DocumentReference>()

  private fun refFor(uid: String) = refs.getOrPut(uid) { mockk() }

  private val ref = refFor("u1")
  private val repository = FirestorePublicProfileRepository(db)

  /** A fictional student: no real data in tests. */
  private val profile =
      PublicProfile(
          uid = "u1",
          displayName = "Test Student",
          section = "IN",
          year = "BA3",
          interests = setOf("music", "sports"),
      )

  private val storedFields =
      mapOf(
          "displayName" to "Test Student",
          "section" to "IN",
          "year" to "BA3",
          "interests" to listOf("music", "sports"),
      )

  init {
    // The literal name, not the constant: it is the collection the Security Rules protect.
    every { db.collection("publicProfiles") } returns profiles
    every { profiles.document(any()) } answers { refFor(firstArg()) }
  }

  private fun firestoreError(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException(code.name, code)

  private fun offline() = firestoreError(FirebaseFirestoreException.Code.UNAVAILABLE)

  private fun denied() = firestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED)

  /** What Firestore throws when it is used after being shut down: not a FirebaseException. */
  private fun terminated() = IllegalStateException("The client has already been terminated")

  /** A stored `publicProfiles/[id]` document with [fields], or a missing one when null. */
  private fun snapshot(id: String = "u1", fields: Map<String, Any?>? = storedFields) =
      mockk<DocumentSnapshot> {
        every { this@mockk.id } returns id
        every { exists() } returns (fields != null)
        every { getString(any()) } answers { fields?.get(firstArg()) as String? }
        every { this@mockk.get(any<String>()) } answers { fields?.get(firstArg()) }
      }

  /** Each UID in [stored] reads as its document; any other UID reads as a missing document. */
  private fun documentsSee(vararg uids: String, stored: Map<String, Map<String, Any?>>) {
    for (uid in uids) every { refFor(uid).get() } returns
        Tasks.forResult(snapshot(uid, stored[uid]))
  }

  // ---- getPublicProfile ----

  @Test
  fun getPublicProfile_readsTheDocumentOfTheGivenUid() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot())

    assertEquals(PublicProfileResult.Found(profile), repository.getPublicProfile("u1"))
    verify { profiles.document("u1") }
  }

  @Test
  fun getPublicProfile_withNoInterestsYet_readsAnEmptySet() = runTest {
    every { ref.get() } returns
        Tasks.forResult(snapshot(fields = storedFields + ("interests" to emptyList<String>())))

    assertEquals(
        PublicProfileResult.Found(profile.copy(interests = emptySet())),
        repository.getPublicProfile("u1"),
    )
  }

  @Test
  fun getPublicProfile_anInterestNoLongerInTheList_isLeftOut() = runTest {
    // A code removed from INTERESTS later may still be in other students' copies; a screen that
    // labels their interests must never get one it has no label for.
    every { refFor("u1").get() } returns
        Tasks.forResult(snapshot(fields = storedFields + ("interests" to listOf("music", "chess"))))

    assertEquals(
        PublicProfileResult.Found(profile.copy(interests = setOf("music"))),
        repository.getPublicProfile("u1"),
    )
  }

  @Test
  fun getPublicProfile_missingDocument_returnsNotFound() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(fields = null))

    assertEquals(PublicProfileResult.NotFound, repository.getPublicProfile("u1"))
  }

  @Test
  fun getPublicProfile_offlineAndNotCached_returnsNetworkError() = runTest {
    every { ref.get() } returns Tasks.forException(offline())

    assertEquals(PublicProfileResult.NetworkError, repository.getPublicProfile("u1"))
  }

  @Test
  fun getPublicProfile_deniedByTheRules_returnsUnexpectedError() = runTest {
    every { ref.get() } returns Tasks.forException(denied())

    assertEquals(PublicProfileResult.UnexpectedError, repository.getPublicProfile("u1"))
  }

  @Test
  fun getPublicProfile_unexpectedException_returnsUnexpectedError() = runTest {
    every { ref.get() } throws terminated()

    assertEquals(PublicProfileResult.UnexpectedError, repository.getPublicProfile("u1"))
  }

  @Test
  fun getPublicProfile_cancelledTask_returnsUnexpectedError() = runTest {
    every { ref.get() } returns Tasks.forCanceled()

    assertEquals(PublicProfileResult.UnexpectedError, repository.getPublicProfile("u1"))
  }

  @Test
  fun getPublicProfile_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { ref.get() } returns TaskCompletionSource<DocumentSnapshot>().task
    var result: PublicProfileResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.getPublicProfile("u1") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  @Test
  fun getPublicProfile_aMissingOrWrongTypedField_returnsUnexpectedError() = runTest {
    for ((name, fields) in
        listOf(
            "no displayName" to storedFields - "displayName",
            "no section" to storedFields - "section",
            "no year" to storedFields - "year",
            "no interests" to storedFields - "interests",
            "interests not a list" to storedFields + ("interests" to "music"),
            "an interest not a string" to storedFields + ("interests" to listOf("music", 3L)),
        )) {
      every { ref.get() } returns Tasks.forResult(snapshot(fields = fields))

      assertEquals(name, PublicProfileResult.UnexpectedError, repository.getPublicProfile("u1"))
    }
  }

  @Test
  fun getPublicProfile_aFieldFirestoreCantReadAsAString_returnsUnexpectedError() = runTest {
    val stored = snapshot()
    every { stored.getString("section") } throws RuntimeException("section is not a String")
    every { ref.get() } returns Tasks.forResult(stored)

    assertEquals(PublicProfileResult.UnexpectedError, repository.getPublicProfile("u1"))
  }

  // ---- getPublicProfiles ----

  @Test
  fun getPublicProfiles_noUids_readsNothing() = runTest {
    assertEquals(PublicProfilesResult.Found(emptyMap()), repository.getPublicProfiles(emptyList()))
    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun getPublicProfiles_readsEachDocumentByUid_neverAQuery() = runTest {
    documentsSee(
        "u1",
        "u2",
        stored = mapOf("u1" to storedFields, "u2" to storedFields + ("displayName" to "Other")),
    )

    val result = repository.getPublicProfiles(listOf("u1", "u2"))

    assertEquals(
        PublicProfilesResult.Found(
            mapOf("u1" to profile, "u2" to profile.copy(uid = "u2", displayName = "Other"))
        ),
        result,
    )
    // The rules allow only reads by UID, never a list, so no student directory can be read.
    verify(exactly = 0) { profiles.whereIn(any<FieldPath>(), any()) }
    verify(exactly = 0) { profiles.get() }
  }

  @Test
  fun getPublicProfiles_asksForEachUidOnce() = runTest {
    documentsSee("u1", "u2", stored = mapOf("u1" to storedFields))

    repository.getPublicProfiles(listOf("u1", "u1", "u2", "u1"))

    verify(exactly = 1) { refFor("u1").get() }
    verify(exactly = 1) { refFor("u2").get() }
  }

  @Test
  fun getPublicProfiles_readsTheDocumentsInParallel() = runTest {
    val first = TaskCompletionSource<DocumentSnapshot>()
    val second = TaskCompletionSource<DocumentSnapshot>()
    every { refFor("u1").get() } returns first.task
    every { refFor("u2").get() } returns second.task

    val call =
        async(start = CoroutineStart.UNDISPATCHED) {
          repository.getPublicProfiles(listOf("u1", "u2"))
        }
    testScheduler.runCurrent()

    // Both reads started before either answered.
    verify { refFor("u1").get() }
    verify { refFor("u2").get() }
    second.setResult(snapshot("u2", null))
    first.setResult(snapshot("u1"))
    assertEquals(PublicProfilesResult.Found(mapOf("u1" to profile)), call.await())
  }

  @Test
  fun getPublicProfiles_leavesOutTheMissingOnes() = runTest {
    documentsSee("u1", "missing", stored = mapOf("u1" to storedFields))

    assertEquals(
        PublicProfilesResult.Found(mapOf("u1" to profile)),
        repository.getPublicProfiles(listOf("u1", "missing")),
    )
  }

  @Test
  fun getPublicProfiles_leavesOutAMalformedOne_andKeepsTheOthers() = runTest {
    documentsSee(
        "u1",
        "broken",
        stored = mapOf("u1" to storedFields, "broken" to storedFields - "section"),
    )

    assertEquals(
        PublicProfilesResult.Found(mapOf("u1" to profile)),
        repository.getPublicProfiles(listOf("u1", "broken")),
    )
  }

  @Test
  fun getPublicProfiles_leavesOutOneFirestoreCantRead_andKeepsTheOthers() = runTest {
    val unreadable = snapshot("broken")
    every { unreadable.getString("section") } throws RuntimeException("section is not a String")
    every { refFor("u1").get() } returns Tasks.forResult(snapshot("u1"))
    every { refFor("broken").get() } returns Tasks.forResult(unreadable)

    assertEquals(
        PublicProfilesResult.Found(mapOf("u1" to profile)),
        repository.getPublicProfiles(listOf("u1", "broken")),
    )
  }

  @Test
  fun getPublicProfiles_oneOfflineAndNotCached_returnsNetworkError_evenIfOthersAreFound() =
      runTest {
        // Offline, a profile that isn't cached may still exist on the server, so no partial answer.
        every { refFor("u1").get() } returns Tasks.forResult(snapshot("u1"))
        every { refFor("u2").get() } returns Tasks.forException(offline())

        assertEquals(
            PublicProfilesResult.NetworkError,
            repository.getPublicProfiles(listOf("u1", "u2")),
        )
      }

  @Test
  fun getPublicProfiles_oneFailing_answersWithoutWaitingForTheOthers() = runTest {
    every { refFor("u1").get() } returns Tasks.forException(offline())
    every { refFor("u2").get() } returns TaskCompletionSource<DocumentSnapshot>().task

    assertEquals(
        PublicProfilesResult.NetworkError,
        repository.getPublicProfiles(listOf("u1", "u2")),
    )
  }

  @Test
  fun getPublicProfiles_deniedByTheRules_returnsUnexpectedError() = runTest {
    every { refFor("u1").get() } returns Tasks.forException(denied())

    assertEquals(PublicProfilesResult.UnexpectedError, repository.getPublicProfiles(listOf("u1")))
  }

  @Test
  fun getPublicProfiles_unexpectedException_returnsUnexpectedError() = runTest {
    every { refFor("u1").get() } throws terminated()

    assertEquals(PublicProfilesResult.UnexpectedError, repository.getPublicProfiles(listOf("u1")))
  }

  @Test
  fun getPublicProfiles_cancelledTask_returnsUnexpectedError() = runTest {
    every { refFor("u1").get() } returns Tasks.forCanceled()

    assertEquals(PublicProfilesResult.UnexpectedError, repository.getPublicProfiles(listOf("u1")))
  }

  @Test
  fun getPublicProfiles_oneCancelledTaskWhileAnotherIsPending_returnsUnexpectedError() = runTest {
    every { refFor("u1").get() } returns Tasks.forCanceled()
    every { refFor("u2").get() } returns TaskCompletionSource<DocumentSnapshot>().task

    assertEquals(
        PublicProfilesResult.UnexpectedError,
        repository.getPublicProfiles(listOf("u1", "u2")),
    )
  }

  @Test
  fun getPublicProfiles_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { refFor("u1").get() } returns TaskCompletionSource<DocumentSnapshot>().task
    var result: PublicProfilesResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) {
          result = repository.getPublicProfiles(listOf("u1"))
        }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }
}
