// Contributors: Claude (profile repository tests against the Firestore emulator, #119; offline,
// wrong-typed field and createdAt tests after an adversarial test review).
package com.polysocial.emulator

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.USERS_COLLECTION
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreUserProfileRepository] against the Firestore and Auth emulators, so its
 * transactions, field names and types go through the real Firestore and the Security Rules (#35).
 *
 * The rules themselves (other uids, unverified emails, locked fields) are covered in detail by
 * firebase/firestore/firestore.rules.test.js; here only the flag rules are checked, as #119 asks.
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

  /**
   * Creates `users/[uid]` as an admin, with every field of a valid profile, then [overrides], minus
   * the fields in [omit].
   */
  private suspend fun createAsAdmin(
      uid: String,
      email: String,
      overrides: Map<String, Any> = emptyMap(),
      omit: Set<String> = emptySet(),
  ) =
      emulator.createAsAdmin(
          USERS_COLLECTION,
          uid,
          mapOf(
              "uid" to uid,
              "email" to email,
              "displayName" to "Test Association",
              "section" to "IN",
              "year" to "BA3",
              "accountType" to "association",
              "createdAt" to CREATED_AT,
          ) + overrides - omit,
      )

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
    // Set by the server, so the profile really reached the emulator.
    assertNotNull(first.createdAt)

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
  fun createProfile_forAnotherUid_returnsUnexpectedError() = runBlocking {
    val profile = signInStudent()

    // The transaction's first read of the other uid's document is denied, so no write is tried.
    // That the create rule also denies the write is checked by the rules tests ("a transaction can
    // create your own users/{uid}, not read or write another's").
    assertEquals(
        CreateProfileResult.UnexpectedError,
        repository.createProfile(student(OTHER_UID)),
    )
    // The same student can create their own profile, so the error comes from the other uid.
    assertEquals(CreateProfileResult.Created, repository.createProfile(profile))
  }

  @Test
  fun createProfile_offline_returnsNetworkErrorAndWritesNothing() = runBlocking {
    val profile = signInStudent()

    val result = offline(profile.uid) { repository.createProfile(profile) }

    assertEquals(CreateProfileResult.NetworkError, result)
    assertEquals(ProfileResult.NotFound, repository.getProfile(profile.uid))
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
  fun updateProfile_offline_returnsNetworkErrorAndKeepsTheProfile() = runBlocking {
    val profile = signInStudent()
    repository.createProfile(profile)

    val result = offline(profile.uid) { repository.updateProfile(profile.copy(year = "MA1")) }

    assertEquals(UpdateProfileResult.NetworkError, result)
    assertEquals("BA3", storedProfile(profile.uid).year)
  }

  @Test
  fun getProfile_readsEveryStoredField_includingTheFlagAnAdminSet() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    createAsAdmin(uid, ASSOCIATION_EMAIL, mapOf("isAssociationVerified" to true))

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
  fun getProfile_withAWrongTypedField_returnsUnexpectedError() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    createAsAdmin(uid, ASSOCIATION_EMAIL, mapOf("year" to 3))

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile(uid))
  }

  @Test
  fun getProfile_withAMissingField_returnsUnexpectedError() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    // Only displayName is missing, so the error can only come from that field.
    createAsAdmin(uid, ASSOCIATION_EMAIL, omit = setOf("displayName"))

    assertEquals(ProfileResult.UnexpectedError, repository.getProfile(uid))
  }

  @Test
  fun getProfile_withTheFlagStoredAsAString_readsAsNotVerified() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    // An admin typo in the console: the string "true" instead of the boolean (#45).
    createAsAdmin(uid, ASSOCIATION_EMAIL, mapOf("isAssociationVerified" to "true"))

    val stored = storedProfile(uid)

    assertFalse(stored.isAssociationVerified)
    assertFalse(stored.isVerifiedAssociation)
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

  /**
   * Runs [block] with the device in airplane mode, so the emulators can't be reached, then turns
   * the network back on and waits until [uid]'s document can be read from the server again.
   *
   * `FirebaseFirestore.disableNetwork()` is not enough: transactions still reach the server with
   * it, so they would succeed instead of failing (see CONTEXT.md, Traps).
   */
  private suspend fun <T> offline(uid: String, block: suspend () -> T): T {
    setAirplaneMode(true)
    try {
      waitUntil("the device to go offline") { !hasNetwork() }
      return block()
    } finally {
      setAirplaneMode(false)
      waitUntil("the emulators to be reachable again") {
        hasNetwork() && runCatchingFirestore { document(uid).get(Source.SERVER).await() }
      }
    }
  }

  private fun setAirplaneMode(enabled: Boolean) {
    val command = "cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}"
    val output =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    // Reading the output to its end waits until the command has finished.
    ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
  }

  private fun hasNetwork(): Boolean {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivity.activeNetwork != null
  }

  /** Whether [read] succeeds; a Firestore failure such as UNAVAILABLE counts as not yet. */
  private suspend fun runCatchingFirestore(read: suspend () -> Unit): Boolean =
      try {
        read()
        true
      } catch (e: FirebaseFirestoreException) {
        false
      }

  /** Polls [condition] until it holds, failing with a clear message after [TIMEOUT_MS]. */
  private suspend fun waitUntil(what: String, condition: suspend () -> Boolean) {
    var waited = 0L
    while (!condition()) {
      if (waited >= TIMEOUT_MS) fail("Timed out after ${TIMEOUT_MS / 1000} s waiting for $what")
      delay(POLL_MS)
      waited += POLL_MS
    }
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
    const val TIMEOUT_MS = 30_000L
    const val POLL_MS = 250L

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
