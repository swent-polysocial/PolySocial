// Contributors: Claude Opus 5.5 (wrote these tests; cancelled tasks and wrong-typed fields);
// Claude (isAssociationVerified tests, #45; unexpected failures and cancelled writes, #182; the
// public profile written with the private one, written test-first, #229; interests, test-first,
// #230); Claude (testing agent: edited interests stored in the fixed order, #230); Claude
// (ensurePublicProfile, the missing public profile of a profile saved before #229, test-first,
// #291).
package com.polysocial.model.user

import com.google.android.gms.tasks.TaskCompletionSource
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreUserProfileRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val users = mockk<CollectionReference>()
  private val ref = mockk<DocumentReference>()
  private val publicProfiles = mockk<CollectionReference>()
  private val publicRef = mockk<DocumentReference>()
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
    // The literal name, not the constant: it is the collection the Security Rules protect.
    every { db.collection("publicProfiles") } returns publicProfiles
    every { publicProfiles.document("u1") } returns publicRef
  }

  /**
   * The `publicProfiles/u1` the rules accept for [profile]: a copy, never the email (#229), with
   * the interests in the fixed list's order (#230).
   */
  private fun publicFieldsOf(profile: UserProfile) =
      mapOf(
          "displayName" to profile.displayName,
          "section" to profile.section,
          "year" to profile.year,
          "interests" to INTERESTS.filter { it in profile.interests },
      )

  private fun offline() =
      FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)

  private fun denied() =
      FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

  /** A stored `users/{uid}` document with [fields], or a missing one when [fields] is null. */
  private fun snapshot(fields: Map<String, Any?>?): DocumentSnapshot = mockk {
    every { exists() } returns (fields != null)
    every { getString(any()) } answers { fields?.get(firstArg()) as String? }
    every { this@mockk.get(any<String>()) } answers { fields?.get(firstArg()) }
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
    every { transaction.set(publicRef, any()) } returns transaction
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
  fun getProfile_readsAVerifiedAssociation() = runTest {
    every { ref.get() } returns
        Tasks.forResult(
            snapshot(
                storedFields + ("accountType" to "association") + ("isAssociationVerified" to true)
            )
        )

    val result = repository.getProfile("u1") as ProfileResult.Found

    assertTrue(result.profile.isAssociationVerified)
    assertTrue(result.profile.isVerifiedAssociation)
  }

  @Test
  fun getProfile_withoutTheVerifiedFlag_isNotVerified() = runTest {
    every { ref.get() } returns
        Tasks.forResult(snapshot(storedFields + ("accountType" to "association")))

    val result = repository.getProfile("u1") as ProfileResult.Found

    assertFalse(result.profile.isAssociationVerified)
  }

  @Test
  fun getProfile_verifiedFlagOfTheWrongType_readsAsNotVerified() = runTest {
    every { ref.get() } returns
        Tasks.forResult(
            snapshot(
                storedFields +
                    ("accountType" to "association") +
                    ("isAssociationVerified" to "true")
            )
        )

    val result = repository.getProfile("u1") as ProfileResult.Found

    assertFalse(result.profile.isAssociationVerified)
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
            "interests" to emptyList<String>(),
        ),
        written.captured,
    )
  }

  @Test
  fun createProfile_writesTheInterests_inTheFixedListsOrder_toBothProfiles() = runTest {
    transactionsSee(snapshot(null))
    val private = slot<Any>()
    val public = slot<Any>()
    every { transaction.set(ref, capture(private)) } returns transaction
    every { transaction.set(publicRef, capture(public)) } returns transaction

    repository.createProfile(profile.copy(interests = setOf("tech", "music", "sports")))

    // The rules compare the two lists with ==, so both must be in the same, fixed order.
    val ordered = listOf("sports", "music", "tech")
    assertEquals(ordered, (private.captured as Map<*, *>)["interests"])
    assertEquals(ordered, (public.captured as Map<*, *>)["interests"])
  }

  @Test
  fun createProfile_anUnknownInterest_isNotWritten() = runTest {
    transactionsSee(snapshot(null))
    val private = slot<Any>()
    every { transaction.set(ref, capture(private)) } returns transaction

    repository.createProfile(profile.copy(interests = setOf("music", "chess")))

    assertEquals(listOf("music"), (private.captured as Map<*, *>)["interests"])
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
  fun createProfile_aClaimedVerification_isNotWritten() = runTest {
    transactionsSee(snapshot(null))
    val written = slot<Any>()
    every { transaction.set(ref, capture(written)) } returns transaction

    repository.createProfile(
        profile.copy(accountType = AccountType.ASSOCIATION, isAssociationVerified = true)
    )

    assertFalse((written.captured as Map<*, *>).containsKey("isAssociationVerified"))
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

  @Test
  fun createProfile_aStudent_alsoWritesTheirPublicProfile_inTheSameTransaction() = runTest {
    transactionsSee(snapshot(null))
    val written = slot<Any>()
    every { transaction.set(publicRef, capture(written)) } returns transaction

    assertEquals(CreateProfileResult.Created, repository.createProfile(profile))

    verify { publicProfiles.document("u1") }
    verify(exactly = 1) { db.runTransaction(any<Transaction.Function<Boolean>>()) }
    assertEquals(publicFieldsOf(profile), written.captured)
  }

  @Test
  fun createProfile_anAssociation_writesNoPublicProfile() = runTest {
    transactionsSee(snapshot(null))

    repository.createProfile(profile.copy(accountType = AccountType.ASSOCIATION))

    verify(exactly = 1) { transaction.set(ref, any()) }
    verify(exactly = 0) { transaction.set(publicRef, any()) }
  }

  // ---- updateProfile ----

  @Test
  fun updateProfile_aStudent_alsoWritesTheirPublicProfile_withTheNewValues() = runTest {
    transactionsSee(snapshot(storedFields))
    val written = slot<Any>()
    every { transaction.set(publicRef, capture(written)) } returns transaction
    val edited = profile.copy(displayName = "New Name", section = "SC", year = "MA1")

    assertEquals(UpdateProfileResult.Updated, repository.updateProfile(edited))

    verify(exactly = 1) { db.runTransaction(any<Transaction.Function<Boolean>>()) }
    assertEquals(publicFieldsOf(edited), written.captured)
  }

  @Test
  fun updateProfile_aStoredProfileWithoutAnAccountType_isAStudents() = runTest {
    transactionsSee(snapshot(storedFields - "accountType"))

    repository.updateProfile(profile)

    verify(exactly = 1) { transaction.set(publicRef, publicFieldsOf(profile)) }
  }

  @Test
  fun updateProfile_anAssociation_writesNoPublicProfile_whateverTheCallerSays() = runTest {
    // The stored account type decides, not the one in the profile passed in.
    transactionsSee(snapshot(storedFields + ("accountType" to "association")))

    assertEquals(UpdateProfileResult.Updated, repository.updateProfile(profile))

    verify(exactly = 1) { transaction.update(ref, any<Map<String, Any>>()) }
    verify(exactly = 0) { transaction.set(publicRef, any()) }
  }

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
        mapOf(
            "displayName" to "New Name",
            "section" to "SC",
            "year" to "MA1",
            "interests" to emptyList<String>(),
        ),
        written.captured,
    )
  }

  @Test
  fun updateProfile_writesTheInterests_toBothProfiles() = runTest {
    transactionsSee(snapshot(storedFields))
    val private = slot<Map<String, Any>>()
    val public = slot<Any>()
    every { transaction.update(ref, capture(private)) } returns transaction
    every { transaction.set(publicRef, capture(public)) } returns transaction
    val edited = profile.copy(interests = setOf("cinema", "games"))

    assertEquals(UpdateProfileResult.Updated, repository.updateProfile(edited))

    assertEquals(listOf("cinema", "games"), private.captured["interests"])
    assertEquals(publicFieldsOf(edited), public.captured)
  }

  @Test
  fun updateProfile_writesTheInterests_inTheFixedListsOrder_withoutUnknownOnes() = runTest {
    transactionsSee(snapshot(storedFields))
    val private = slot<Map<String, Any>>()
    val public = slot<Any>()
    every { transaction.update(ref, capture(private)) } returns transaction
    every { transaction.set(publicRef, capture(public)) } returns transaction

    repository.updateProfile(
        profile.copy(interests = linkedSetOf("volunteering", "chess", "sports"))
    )

    // The rules compare the two lists with == and refuse unknown codes.
    val ordered = listOf("sports", "volunteering")
    assertEquals(ordered, private.captured["interests"])
    assertEquals(ordered, (public.captured as Map<*, *>)["interests"])
  }

  @Test
  fun updateProfile_missingProfile_returnsNotFoundAndWritesNothing() = runTest {
    transactionsSee(snapshot(null))

    assertEquals(UpdateProfileResult.NotFound, repository.updateProfile(profile))

    verify(exactly = 0) { transaction.update(any(), any<Map<String, Any>>()) }
    verify(exactly = 0) { transaction.set(any(), any()) }
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

  // ---- Failures that are not FirebaseExceptions ----

  @Test
  fun getProfile_cancelledTask_returnsUnexpectedError() = runTest {
    every { ref.get() } returns Tasks.forCanceled()

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile("u1"))
  }

  @Test
  fun createProfile_cancelledTask_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns Tasks.forCanceled()

    assertEquals(CreateProfileResult.UnexpectedError, repository.createProfile(profile))
  }

  @Test
  fun updateProfile_cancelledTask_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns Tasks.forCanceled()

    assertEquals(UpdateProfileResult.UnexpectedError, repository.updateProfile(profile))
  }

  @Test
  fun getProfile_readsTheInterests() = runTest {
    every { ref.get() } returns
        Tasks.forResult(snapshot(storedFields + ("interests" to listOf("tech", "music"))))

    val result = repository.getProfile("u1")

    assertEquals(setOf("music", "tech"), (result as ProfileResult.Found).profile.interests)
  }

  @Test
  fun getProfile_aProfileSavedBeforeInterests_hasNone() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields))

    val result = repository.getProfile("u1")

    assertEquals(emptySet<String>(), (result as ProfileResult.Found).profile.interests)
  }

  @Test
  fun getProfile_anUnknownInterest_isLeftOut() = runTest {
    // A code removed from the list later must not make the profile unreadable, nor be saved back,
    // which the rules would refuse.
    every { ref.get() } returns
        Tasks.forResult(snapshot(storedFields + ("interests" to listOf("music", "chess"))))

    val result = repository.getProfile("u1")

    assertEquals(setOf("music"), (result as ProfileResult.Found).profile.interests)
  }

  @Test
  fun getProfile_interestsThatArentAList_returnsUnexpectedError() = runTest {
    every { ref.get() } returns Tasks.forResult(snapshot(storedFields + ("interests" to "music")))

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile("u1"))
  }

  @Test
  fun getProfile_anInterestThatIsntAString_returnsUnexpectedError() = runTest {
    every { ref.get() } returns
        Tasks.forResult(snapshot(storedFields + ("interests" to listOf("music", 7L))))

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile("u1"))
  }

  @Test
  fun getProfile_fieldOfTheWrongType_returnsUnexpectedError() = runTest {
    val stored = snapshot(storedFields)
    every { stored.getString("section") } throws RuntimeException("section is not a String")
    every { ref.get() } returns Tasks.forResult(stored)

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile("u1"))
  }

  @Test
  fun cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { ref.get() } returns TaskCompletionSource<DocumentSnapshot>().task
    var result: ProfileResult? = null
    val call = launch(start = CoroutineStart.UNDISPATCHED) { result = repository.getProfile("u1") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  // ---- Unexpected failures (#182) ----

  /** What Firestore throws when it is used after being shut down: not a FirebaseException. */
  private fun terminated() = IllegalStateException("The client has already been terminated")

  @Test
  fun getProfile_unexpectedException_returnsUnexpectedError() = runTest {
    every { ref.get() } throws terminated()

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile("u1"))
  }

  @Test
  fun createProfile_unexpectedException_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } throws terminated()

    assertEquals(CreateProfileResult.UnexpectedError, repository.createProfile(profile))
  }

  @Test
  fun updateProfile_unexpectedFailureOfTheTask_returnsUnexpectedError() = runTest {
    transactionsFail(IllegalArgumentException("not a valid document"))

    assertEquals(UpdateProfileResult.UnexpectedError, repository.updateProfile(profile))
  }

  @Test
  fun createProfile_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns
        TaskCompletionSource<Boolean>().task
    var result: CreateProfileResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.createProfile(profile) }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  @Test
  fun updateProfile_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { db.runTransaction(any<Transaction.Function<Boolean>>()) } returns
        TaskCompletionSource<Boolean>().task
    var result: UpdateProfileResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.updateProfile(profile) }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  // ---- ensurePublicProfile (#291) ----

  private val publicSnapshot = mockk<DocumentSnapshot>()

  /**
   * Runs every transaction against [transaction], where `users/u1` is [stored] and
   * `publicProfiles/u1` exists when [publicExists].
   */
  private fun ensureTransactionsSee(stored: DocumentSnapshot, publicExists: Boolean = false) {
    every { publicSnapshot.exists() } returns publicExists
    every { transaction.get(ref) } returns stored
    every { transaction.get(publicRef) } returns publicSnapshot
    every { transaction.set(publicRef, any()) } returns transaction
    every { db.runTransaction(any<Transaction.Function<EnsurePublicProfileResult>>()) } answers
        {
          Tasks.forResult(
              firstArg<Transaction.Function<EnsurePublicProfileResult>>().apply(transaction)
          )
        }
  }

  private fun ensureTransactionsFail(error: Exception) {
    every { db.runTransaction(any<Transaction.Function<EnsurePublicProfileResult>>()) } returns
        Tasks.forException(error)
  }

  /** Nothing at all was written in the transaction, to either profile. */
  private fun verifyNothingWritten() {
    verify(exactly = 0) { transaction.set(any(), any()) }
    verify(exactly = 0) { transaction.update(any(), any<Map<String, Any>>()) }
    verify(exactly = 0) { transaction.delete(any()) }
  }

  @Test
  fun ensurePublicProfile_aStudentWithoutOne_getsACopyOfTheStoredProfile_withoutTheEmail() =
      runTest {
        // A profile saved before #229, and so before #230's interests.
        ensureTransactionsSee(snapshot(storedFields + ("displayName" to "Stored Name")))

        assertEquals(EnsurePublicProfileResult.Created, repository.ensurePublicProfile("u1"))

        verify(exactly = 1) {
          transaction.set(
              publicRef,
              mapOf(
                  "displayName" to "Stored Name",
                  "section" to "IN",
                  "year" to "BA3",
                  "interests" to emptyList<String>(),
              ),
          )
        }
      }

  @Test
  fun ensurePublicProfile_neverWritesThePrivateProfile() = runTest {
    ensureTransactionsSee(snapshot(storedFields))

    assertEquals(EnsurePublicProfileResult.Created, repository.ensurePublicProfile("u1"))

    verify(exactly = 0) { transaction.set(ref, any()) }
    verify(exactly = 0) { transaction.update(ref, any<Map<String, Any>>()) }
  }

  @Test
  fun ensurePublicProfile_copiesTheStoredInterests_inTheFixedListsOrder() = runTest {
    ensureTransactionsSee(snapshot(storedFields + ("interests" to listOf("music", "tech"))))

    assertEquals(EnsurePublicProfileResult.Created, repository.ensurePublicProfile("u1"))

    verify(exactly = 1) {
      transaction.set(
          publicRef,
          publicFieldsOf(profile.copy(interests = setOf("tech", "music"))),
      )
    }
  }

  @Test
  fun ensurePublicProfile_aStoredProfileWithoutAnAccountType_isUnreadable_andWritesNothing() =
      runTest {
        // getProfile can't read it either, so app start never gets this far with one.
        ensureTransactionsSee(snapshot(storedFields - "accountType"))

        assertEquals(
            EnsurePublicProfileResult.UnexpectedError,
            repository.ensurePublicProfile("u1"),
        )
        verifyNothingWritten()
      }

  @Test
  fun ensurePublicProfile_anExistingPublicProfile_isLeftUnchanged() = runTest {
    ensureTransactionsSee(snapshot(storedFields), publicExists = true)

    assertEquals(EnsurePublicProfileResult.NotNeeded, repository.ensurePublicProfile("u1"))

    verifyNothingWritten()
  }

  @Test
  fun ensurePublicProfile_anAssociation_getsNone_andItsPublicProfileIsNeverRead() = runTest {
    // An association may not read publicProfiles at all: the read would fail the transaction.
    ensureTransactionsSee(snapshot(storedFields + ("accountType" to "association")))

    assertEquals(EnsurePublicProfileResult.NotNeeded, repository.ensurePublicProfile("u1"))

    verify(exactly = 0) { transaction.get(publicRef) }
    verifyNothingWritten()
  }

  @Test
  fun ensurePublicProfile_aVerifiedAssociation_getsNoneEither() = runTest {
    ensureTransactionsSee(
        snapshot(
            storedFields + ("accountType" to "association") + ("isAssociationVerified" to true)
        )
    )

    assertEquals(EnsurePublicProfileResult.NotNeeded, repository.ensurePublicProfile("u1"))

    verifyNothingWritten()
  }

  @Test
  fun ensurePublicProfile_withoutAPrivateProfile_returnsNotFound_andWritesNothing() = runTest {
    ensureTransactionsSee(snapshot(null))

    assertEquals(EnsurePublicProfileResult.NotFound, repository.ensurePublicProfile("u1"))

    verifyNothingWritten()
  }

  @Test
  fun ensurePublicProfile_anUnreadablePrivateProfile_returnsUnexpectedError_andWritesNothing() =
      runTest {
        for (field in listOf("displayName", "section", "year")) {
          ensureTransactionsSee(snapshot(storedFields - field))

          assertEquals(
              "without $field",
              EnsurePublicProfileResult.UnexpectedError,
              repository.ensurePublicProfile("u1"),
          )
        }
        verifyNothingWritten()
      }

  @Test
  fun ensurePublicProfile_readsTheProfilesOfTheGivenUid() = runTest {
    ensureTransactionsSee(snapshot(storedFields))

    repository.ensurePublicProfile("u1")

    verify { users.document("u1") }
    verify { publicProfiles.document("u1") }
  }

  @Test
  fun ensurePublicProfile_offline_returnsNetworkError() = runTest {
    ensureTransactionsFail(offline())

    assertEquals(EnsurePublicProfileResult.NetworkError, repository.ensurePublicProfile("u1"))
  }

  @Test
  fun ensurePublicProfile_deniedByTheRules_returnsUnexpectedError() = runTest {
    ensureTransactionsFail(denied())

    assertEquals(EnsurePublicProfileResult.UnexpectedError, repository.ensurePublicProfile("u1"))
  }

  @Test
  fun ensurePublicProfile_cancelledTask_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<EnsurePublicProfileResult>>()) } returns
        Tasks.forCanceled()

    assertEquals(EnsurePublicProfileResult.UnexpectedError, repository.ensurePublicProfile("u1"))
  }

  @Test
  fun ensurePublicProfile_unexpectedException_returnsUnexpectedError() = runTest {
    every { db.runTransaction(any<Transaction.Function<EnsurePublicProfileResult>>()) } throws
        terminated()

    assertEquals(EnsurePublicProfileResult.UnexpectedError, repository.ensurePublicProfile("u1"))
  }

  @Test
  fun ensurePublicProfile_cancellingTheCaller_stillCancelsTheCall() = runTest {
    every { db.runTransaction(any<Transaction.Function<EnsurePublicProfileResult>>()) } returns
        TaskCompletionSource<EnsurePublicProfileResult>().task
    var result: EnsurePublicProfileResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) {
          result = repository.ensurePublicProfile("u1")
        }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }
}
