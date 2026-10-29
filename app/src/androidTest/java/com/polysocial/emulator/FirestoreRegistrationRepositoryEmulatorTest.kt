// Contributors: Claude Opus 5.5 (registration repository tests against the rules, #121).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.registration.ATTENDEES_COLLECTION
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FirestoreRegistrationRepository
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.user.USERS_COLLECTION
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreRegistrationRepository] against the Firestore and Auth emulators, so its
 * transactions, its collection-group query and the attendee rules (#121) run on the real Firestore.
 * Events and other students' registrations are seeded as an admin would, bypassing the rules.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreRegistrationRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy {
    FirestoreRegistrationRepository(
        db = emulator.firestore,
        auth = EmulatorSignedInUser(emulator),
        network = AlwaysOnline,
    )
  }

  private suspend fun signInStudent(): String = emulator.signInVerifiedUser(STUDENT_EMAIL).uid

  private suspend fun seed(id: String, event: Event) =
      emulator.createAsAdmin(EVENTS_COLLECTION, id, event.toFirestoreMap())

  private suspend fun seedRegistration(eventId: String, uid: String) =
      emulator.createAsAdmin(
          attendeesOf(eventId),
          uid,
          mapOf("uid" to uid, "registeredAt" to Instant.parse("2030-02-01T12:00:00Z")),
      )

  private suspend fun isRegistered(eventId: String, uid: String) =
      emulator.existsAsAdmin(attendeesOf(eventId), uid)

  /** The first list of the student's registrations read from the server, not the cache. */
  private suspend fun myRegistrations(): Set<String> =
      withTimeout(TIMEOUT_MS) {
        val result =
            repository.observeMyRegistrations().first {
              it !is MyRegistrationsResult.Registrations || !it.fromCache
            }
        assertTrue(
            "Expected registrations, got $result",
            result is MyRegistrationsResult.Registrations,
        )
        (result as MyRegistrationsResult.Registrations).eventIds
      }

  @Test
  fun register_storesTheRegistrationAndTheStudentSeesIt() = runBlocking {
    val uid = signInStudent()
    seed("public", event())

    assertEquals(
        RegisterResult.Registered,
        withTimeout(TIMEOUT_MS) { repository.register("public") },
    )

    assertTrue(isRegistered("public", uid))
    assertEquals(setOf("public"), myRegistrations())
  }

  @Test
  fun register_twiceIsStillRegistered() = runBlocking {
    val uid = signInStudent()
    seed("public", event())

    withTimeout(TIMEOUT_MS) { repository.register("public") }

    assertEquals(
        RegisterResult.Registered,
        withTimeout(TIMEOUT_MS) { repository.register("public") },
    )
    assertTrue(isRegistered("public", uid))
  }

  @Test
  fun register_aPrivateEventTheStudentIsAllowedIn() = runBlocking {
    val uid = signInStudent()
    seed("private", event().copy(isPrivate = true, allowedUids = listOf(ORGANIZER_UID, uid)))

    assertEquals(
        RegisterResult.Registered,
        withTimeout(TIMEOUT_MS) { repository.register("private") },
    )
  }

  @Test
  fun register_aPrivateEventTheStudentIsNotAllowedIn_isNotFoundAndStoresNothing() = runBlocking {
    val uid = signInStudent()
    seed("private", event().copy(isPrivate = true))

    assertEquals(
        RegisterResult.NotFound,
        withTimeout(TIMEOUT_MS) { repository.register("private") },
    )

    assertFalse(isRegistered("private", uid))
  }

  @Test
  fun register_anEventThatDoesNotExist_isNotFoundAndStoresNothing() = runBlocking {
    val uid = signInStudent()

    assertEquals(
        RegisterResult.NotFound,
        withTimeout(TIMEOUT_MS) { repository.register("missing") },
    )

    assertFalse(isRegistered("missing", uid))
  }

  @Test
  fun register_anAssociationCannotRegister() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf("uid" to uid, "accountType" to "association", "isAssociationVerified" to true),
    )
    seed("public", event())

    assertEquals(RegisterResult.NotFound, withTimeout(TIMEOUT_MS) { repository.register("public") })

    assertFalse(isRegistered("public", uid))
  }

  @Test
  fun cancel_removesTheRegistration_andCancellingAgainChangesNothing() = runBlocking {
    val uid = signInStudent()
    seed("public", event())
    seedRegistration("public", uid)

    assertEquals(
        CancelRegistrationResult.Cancelled,
        withTimeout(TIMEOUT_MS) { repository.cancel("public") },
    )
    assertFalse(isRegistered("public", uid))
    assertEquals(emptySet<String>(), myRegistrations())

    assertEquals(
        CancelRegistrationResult.Cancelled,
        withTimeout(TIMEOUT_MS) { repository.cancel("public") },
    )
  }

  @Test
  fun cancel_worksOnceTheEventIsDeleted() = runBlocking {
    val uid = signInStudent()
    seedRegistration("deleted", uid)

    assertEquals(
        CancelRegistrationResult.Cancelled,
        withTimeout(TIMEOUT_MS) { repository.cancel("deleted") },
    )
    assertFalse(isRegistered("deleted", uid))
  }

  @Test
  fun observe_listsOnlyTheStudentsOwnRegistrations() = runBlocking {
    val uid = signInStudent()
    seed("first", event())
    seed("second", event())
    seed("other", event())
    seedRegistration("first", uid)
    seedRegistration("second", uid)
    seedRegistration("second", OTHER_STUDENT_UID)
    seedRegistration("other", OTHER_STUDENT_UID)

    assertEquals(setOf("first", "second"), myRegistrations())
  }

  private fun attendeesOf(eventId: String) = "$EVENTS_COLLECTION/$eventId/$ATTENDEES_COLLECTION"

  private companion object {
    /** Fictional accounts and events: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val ORGANIZER_UID = "fictional-organizer"
    const val OTHER_STUDENT_UID = "fictional-student"
    const val TIMEOUT_MS = 10_000L

    fun event() =
        Event(
            title = "Test event",
            description = "A fictional event.",
            category = EventCategory.STUDY,
            location = Coordinates(46.5191, 6.5668),
            startTime = Instant.parse("2030-03-02T18:00:00Z"),
            isPrivate = false,
            createdBy = ORGANIZER_UID,
            organizerIds = listOf(ORGANIZER_UID),
            allowedUids = listOf(ORGANIZER_UID),
        )
  }
}
