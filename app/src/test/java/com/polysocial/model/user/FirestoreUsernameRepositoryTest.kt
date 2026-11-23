// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #448).
package com.polysocial.model.user

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FirestoreUsernameRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val usernames = mockk<CollectionReference>()
  private val users = mockk<CollectionReference>()
  private val publicProfiles = mockk<CollectionReference>()
  private val profileRef = mockk<DocumentReference>()
  private val publicRef = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>(relaxed = true)

  /** One mocked `usernames/{name}` reference per name, created on first use. */
  private val nameRefs = mutableMapOf<String, DocumentReference>()

  private fun nameRef(name: String) = nameRefs.getOrPut(name) { mockk() }

  private val auth = FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository = FirestoreUsernameRepository(db, auth, network)

  /** A fictional student's stored `users/alice`. */
  private val storedProfile =
      mapOf(
          "uid" to "alice",
          "email" to "alice@epfl.ch",
          "displayName" to "Test Student",
          "section" to "IN",
          "year" to "BA3",
          "accountType" to "student",
      )

  private fun snapshot(fields: Map<String, Any?>?) =
      mockk<DocumentSnapshot> {
        every { exists() } returns (fields != null)
        every { getString(any()) } answers { fields?.get(firstArg()) as String? }
        every { this@mockk.get(any<String>()) } answers { fields?.get(firstArg()) }
      }

  /** What the transaction reads: `users/alice`, `publicProfiles/alice` and `usernames/{name}`. */
  private fun stored(
      profile: Map<String, Any?>? = storedProfile,
      hasPublicProfile: Boolean = true,
      names: Map<String, String> = emptyMap(),
  ) {
    every { transaction.get(any()) } answers
        {
          when (val ref = firstArg<DocumentReference>()) {
            profileRef -> snapshot(profile)
            publicRef ->
                snapshot(if (hasPublicProfile) mapOf("displayName" to "Test Student") else null)
            else ->
                snapshot(
                    nameRefs.entries
                        .firstOrNull { it.value == ref }
                        ?.let { names[it.key] }
                        ?.let { mapOf("uid" to it) }
                )
          }
        }
  }

  @Before
  fun stubFirestore() {
    // The literal names, not the constants: they are the collections the Security Rules protect.
    every { db.collection("usernames") } returns usernames
    every { usernames.document(any()) } answers { nameRef(firstArg()) }
    every { db.collection("users") } returns users
    every { users.document("alice") } returns profileRef
    every { db.collection("publicProfiles") } returns publicProfiles
    every { publicProfiles.document("alice") } returns publicRef
    stored()
    every { db.runTransaction(any<Transaction.Function<Any>>()) } answers
        {
          Tasks.forResult(firstArg<Transaction.Function<Any>>().apply(transaction))
        }
  }

  private fun failed(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException("failed", code)

  private fun verifyNothingWritten() =
      verify(exactly = 0) {
        transaction.set(any(), any())
        transaction.update(any(), any<String>(), any())
        transaction.update(any(), any<Map<String, Any>>())
        transaction.delete(any())
      }

  private fun verifyNoTransaction() =
      verify(exactly = 0) { db.runTransaction(any<Transaction.Function<Any>>()) }

  // ---- setUsername ----

  @Test
  fun setUsername_aFreeName_isClaimedWithBothProfiles_afterTheReads() = runTest {
    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))

    verifyOrder {
      transaction.get(profileRef)
      transaction.get(nameRef("franek"))
      transaction.get(publicRef)
      transaction.set(nameRef("franek"), mapOf("uid" to "alice"))
      transaction.update(profileRef, "username", "franek")
      transaction.update(publicRef, "username", "franek")
    }
    verify(exactly = 0) { transaction.delete(any()) }
  }

  @Test
  fun setUsername_aRename_freesThePreviousName_inTheSameTransaction() = runTest {
    stored(
        profile = storedProfile + ("username" to "old.name"),
        names = mapOf("old.name" to "alice"),
    )

    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))

    verify(exactly = 1) { transaction.set(nameRef("franek"), mapOf("uid" to "alice")) }
    verify(exactly = 1) { transaction.delete(nameRef("old.name")) }
    verify(exactly = 1) { db.runTransaction(any<Transaction.Function<Any>>()) }
  }

  @Test
  fun setUsername_theNameAlreadyHeld_isSavedWithoutWriting() = runTest {
    stored(profile = storedProfile + ("username" to "franek"), names = mapOf("franek" to "alice"))

    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))

    verifyNothingWritten()
  }

  @Test
  fun setUsername_aNameAnotherStudentHolds_isTaken_andWritesNothing() = runTest {
    stored(names = mapOf("lea" to "bob"))

    assertEquals(SetUsernameResult.Taken, repository.setUsername("lea"))

    verifyNothingWritten()
  }

  @Test
  fun setUsername_aNameDocumentAlreadyTheStudents_isNamedWithoutBeingRewritten() = runTest {
    // The profile doesn't name it: only an admin's edit could leave this state.
    stored(names = mapOf("franek" to "alice"))

    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))

    verify(exactly = 0) { transaction.set(any(), any()) }
    verify(exactly = 1) { transaction.update(profileRef, "username", "franek") }
  }

  @Test
  fun setUsername_withoutAPublicProfile_updatesOnlyThePrivateOne() = runTest {
    // A profile saved before #229.
    stored(hasPublicProfile = false)

    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))

    verify(exactly = 1) { transaction.update(profileRef, "username", "franek") }
    verify(exactly = 0) { transaction.update(publicRef, any<String>(), any()) }
  }

  @Test
  fun setUsername_aStoredPreviousNameThatCantBeOne_isntDeleted() = runTest {
    stored(profile = storedProfile + ("username" to "Not/A name"))

    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))

    verify(exactly = 0) { transaction.delete(any()) }
  }

  @Test
  fun setUsername_aNameTheFormatRefuses_isInvalid_withoutFirestore() = runTest {
    online = false
    auth.user = null

    assertEquals(SetUsernameResult.Invalid(UsernameError.FORMAT), repository.setUsername("9lives"))
    assertEquals(SetUsernameResult.Invalid(UsernameError.RESERVED), repository.setUsername("admin"))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun setUsername_offline_isANetworkError_withoutATransaction() = runTest {
    online = false

    assertEquals(SetUsernameResult.NetworkError, repository.setUsername("franek"))

    verifyNoTransaction()
  }

  @Test
  fun setUsername_nobodySignedIn_isUnexpected_withoutATransaction() = runTest {
    auth.user = null

    assertEquals(SetUsernameResult.UnexpectedError, repository.setUsername("franek"))

    verifyNoTransaction()
  }

  @Test
  fun setUsername_withoutAProfile_isUnexpected_andWritesNothing() = runTest {
    stored(profile = null)

    assertEquals(SetUsernameResult.UnexpectedError, repository.setUsername("franek"))

    verifyNothingWritten()
  }

  @Test
  fun setUsername_anAssociation_isUnexpected_andNeverReadsUsernames() = runTest {
    stored(profile = storedProfile + ("accountType" to "association"))

    assertEquals(SetUsernameResult.UnexpectedError, repository.setUsername("franek"))

    verify(exactly = 0) { transaction.get(nameRef("franek")) }
    verifyNothingWritten()
  }

  @Test
  fun setUsername_aTransactionFailingOffline_isANetworkError() = runTest {
    every { db.runTransaction(any<Transaction.Function<Any>>()) } returns
        Tasks.forException(failed(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(SetUsernameResult.NetworkError, repository.setUsername("franek"))
  }

  @Test
  fun setUsername_aTransactionTheRulesDeny_isUnexpected() = runTest {
    every { db.runTransaction(any<Transaction.Function<Any>>()) } returns
        Tasks.forException(failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(SetUsernameResult.UnexpectedError, repository.setUsername("franek"))
  }

  @Test
  fun setUsername_anyOtherException_isUnexpected() = runTest {
    every { db.runTransaction(any<Transaction.Function<Any>>()) } throws
        IllegalStateException("The client has already been terminated")

    assertEquals(SetUsernameResult.UnexpectedError, repository.setUsername("franek"))
  }

  // ---- lookUp ----

  private fun server(name: String, fields: Map<String, Any?>?) {
    every { nameRef(name).get(Source.SERVER) } returns Tasks.forResult(snapshot(fields))
  }

  @Test
  fun lookUp_aHeldName_isFound_fromTheServerOnly() = runTest {
    server("lea", mapOf("uid" to "bob"))

    assertEquals(UsernameLookupResult.Found("bob"), repository.lookUp("lea"))

    verify(exactly = 1) { nameRef("lea").get(Source.SERVER) }
    verify(exactly = 0) { nameRef("lea").get() }
    verify(exactly = 0) { nameRef("lea").get(Source.CACHE) }
  }

  @Test
  fun lookUp_aFreeName_isNotFound() = runTest {
    server("lea", null)

    assertEquals(UsernameLookupResult.NotFound, repository.lookUp("lea"))
  }

  @Test
  fun lookUp_aNameTheFormatRefuses_isNotFound_withoutARead() = runTest {
    assertEquals(UsernameLookupResult.NotFound, repository.lookUp("@lea"))
    assertEquals(UsernameLookupResult.NotFound, repository.lookUp("admin"))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun lookUp_offline_isANetworkError_withoutARead() = runTest {
    online = false

    assertEquals(UsernameLookupResult.NetworkError, repository.lookUp("lea"))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun lookUp_aReadFailingOffline_isANetworkError() = runTest {
    every { nameRef("lea").get(Source.SERVER) } returns
        Tasks.forException(failed(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(UsernameLookupResult.NetworkError, repository.lookUp("lea"))
  }

  @Test
  fun lookUp_aReadTheRulesDeny_isUnexpected() = runTest {
    every { nameRef("lea").get(Source.SERVER) } returns
        Tasks.forException(failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(UsernameLookupResult.UnexpectedError, repository.lookUp("lea"))
  }

  @Test
  fun lookUp_aDocumentWithoutAUid_isUnexpected() = runTest {
    server("lea", mapOf("uid" to 42L))

    assertEquals(UsernameLookupResult.UnexpectedError, repository.lookUp("lea"))
  }
}
