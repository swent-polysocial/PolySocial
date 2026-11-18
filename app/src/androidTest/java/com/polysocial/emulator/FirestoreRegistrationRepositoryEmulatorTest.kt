// Contributors: Claude Opus 5.5 (registration repository tests against the rules, #121; the ended
// event after review; the organizer's attendee count, #131).
// Contributors: Claude (Opus 5.5, Anthropic) (listing the attendees against the rules, #370;
// counted registrations, full events, bursts and offline writes against the rules, #373, after
// #372's JS SDK prototype by Claude (Fable 5.2 and Opus 5.5, Anthropic)).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import com.polysocial.model.event.ATTENDEE_COUNT_FIELD
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FirestoreEventRepository
import com.polysocial.model.event.UpdateEventResult
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.registration.ATTENDEES_COLLECTION
import com.polysocial.model.registration.AttendeeCountResult
import com.polysocial.model.registration.AttendeesResult
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FirestoreRegistrationRepository
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.USERS_COLLECTION
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreRegistrationRepository] against the Firestore and Auth emulators, so its
 * transactions, its collection-group query and the attendee rules (#121) run on the real Firestore.
 * Events and other students' registrations are seeded as an admin would, bypassing the rules.
 *
 * Registering and cancelling change the event's `attendeeCount` with transactions that read nothing
 * (#372, decision 21). #372's prototype checked them with the JS SDK; these tests check them with
 * the Android SDK: the count, a full event, several students at once (each on its own app in
 * [StudentApps]), and that offline they fail instead of being queued or waiting.
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

  /** Seeds [event] as [id] with [registered] students, counted in its `attendeeCount`. */
  private suspend fun seedCounted(
      id: String,
      event: Event,
      registered: List<String> = emptyList(),
  ) {
    seed(id, event, attendeeCount = registered.size)
    registered.forEach { seedRegistration(id, it) }
  }

  private suspend fun seed(id: String, event: Event, attendeeCount: Int) =
      emulator.createAsAdmin(
          EVENTS_COLLECTION,
          id,
          event.toFirestoreMap() + (ATTENDEE_COUNT_FIELD to attendeeCount),
      )

  /** The event's stored `attendeeCount`, as the signed-in student reads it; null when absent. */
  private suspend fun countOf(eventId: String): Long? =
      emulator.firestore
          .collection(EVENTS_COLLECTION)
          .document(eventId)
          .get(Source.SERVER)
          .await()
          .getLong(ATTENDEE_COUNT_FIELD)

  /**
   * Signs in [count] other students at once, each on its own app, and returns their repositories.
   */
  private suspend fun signInStudents(count: Int): List<FirestoreRegistrationRepository> =
      StudentApps.all.take(count).mapIndexed { index, app ->
        emulator.signInVerifiedUser("burst.student$index@epfl.ch", auth = app.auth)
        FirestoreRegistrationRepository(
            app.firestore,
            EmulatorSignedInUser(emulator, app.auth),
            AlwaysOnline,
        )
      }

  @After fun signOutTheOtherStudents() = StudentApps.signOutAll()

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
  fun register_anEventThatHasEnded_isRefusedAndStoresNothing() = runBlocking {
    val uid = signInStudent()
    seed(
        "ended",
        event()
            .copy(
                startTime = Instant.parse("2020-03-02T18:00:00Z"),
                endTime = Instant.parse("2020-03-02T20:00:00Z"),
            ),
    )

    assertEquals(
        RegisterResult.UnexpectedError,
        withTimeout(TIMEOUT_MS) { repository.register("ended") },
    )

    assertFalse(isRegistered("ended", uid))
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

  @Test
  fun count_aVerifiedAssociationCountsWhoIsGoingToItsOwnEvent() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf("uid" to uid, "accountType" to "association", "isAssociationVerified" to true),
    )
    seed("own", event().withOrganizer(uid).copy(isAssociationEvent = true))
    seedRegistration("own", OTHER_STUDENT_UID)
    seedRegistration("own", "fictional-student-2")
    seedRegistration("other", OTHER_STUDENT_UID)

    assertEquals(
        AttendeeCountResult.Count(2),
        withTimeout(TIMEOUT_MS) { repository.countAttendees("own") },
    )
  }

  @Test
  fun count_aStudentOrganizerCountsToo_andAnEventWithNobodyGoingIsZero() = runBlocking {
    val uid = signInStudent()
    seed("own", event().withOrganizer(uid))

    assertEquals(
        AttendeeCountResult.Count(0),
        withTimeout(TIMEOUT_MS) { repository.countAttendees("own") },
    )
  }

  @Test
  fun count_aStudentWhoDoesntOrganizeTheEvent_isRefused() = runBlocking {
    val uid = signInStudent()
    seed("public", event())
    seedRegistration("public", uid)

    assertEquals(
        AttendeeCountResult.UnexpectedError,
        withTimeout(TIMEOUT_MS) { repository.countAttendees("public") },
    )
  }

  @Test
  fun attendees_aStudentOrganizerListsWhoIsGoingToTheirEventOnly() = runBlocking {
    val uid = signInStudent()
    seed("own", event().withOrganizer(uid))
    seedRegistration("own", OTHER_STUDENT_UID)
    seedRegistration("own", "fictional-student-2")
    seedRegistration("other", "fictional-student-3")

    assertEquals(
        AttendeesResult.Attendees(listOf(OTHER_STUDENT_UID, "fictional-student-2")),
        withTimeout(TIMEOUT_MS) { repository.getAttendeeUids("own") },
    )
  }

  @Test
  fun attendees_aStudentWhoDoesntOrganizeTheEvent_isRefused() = runBlocking {
    val uid = signInStudent()
    seed("public", event())
    seedRegistration("public", uid)

    assertEquals(
        AttendeesResult.UnexpectedError,
        withTimeout(TIMEOUT_MS) { repository.getAttendeeUids("public") },
    )
  }

  @Test
  fun register_raisesTheEventsCount() = runBlocking {
    signInStudent()
    seedCounted("public", event(), listOf(OTHER_STUDENT_UID))

    assertEquals(
        RegisterResult.Registered,
        withTimeout(TIMEOUT_MS) { repository.register("public") },
    )

    assertEquals(2L, countOf("public"))
  }

  @Test
  fun register_anEventWithoutACount_startsItAtOne() = runBlocking {
    signInStudent()
    seed("legacy", event())

    assertEquals(
        RegisterResult.Registered,
        withTimeout(TIMEOUT_MS) { repository.register("legacy") },
    )

    assertEquals(1L, countOf("legacy"))
  }

  @Test
  fun register_aFullEvent_isFullAndStoresNothing() = runBlocking {
    val uid = signInStudent()
    seedCounted("full", event().copy(capacity = 2), listOf(OTHER_STUDENT_UID, SECOND_STUDENT_UID))

    assertEquals(RegisterResult.Full, withTimeout(TIMEOUT_MS) { repository.register("full") })

    assertFalse(isRegistered("full", uid))
    assertEquals(2L, countOf("full"))
  }

  @Test
  fun register_anEventWhoseCapacityWasLoweredBelowTheCount_isFull() = runBlocking {
    signInStudent()
    seedCounted("lowered", event().copy(capacity = 2), listOf(OTHER_STUDENT_UID, "a", "b"))

    assertEquals(RegisterResult.Full, withTimeout(TIMEOUT_MS) { repository.register("lowered") })
    assertEquals(3L, countOf("lowered"))
  }

  @Test
  fun register_alreadyRegisteredForAFullEvent_isStillRegistered() = runBlocking {
    val uid = signInStudent()
    seedCounted("full", event().copy(capacity = 2), listOf(uid, OTHER_STUDENT_UID))

    assertEquals(RegisterResult.Registered, withTimeout(TIMEOUT_MS) { repository.register("full") })

    assertEquals(2L, countOf("full"))
  }

  @Test
  fun cancel_lowersTheCount_andThePlaceCanBeTakenAgain() = runBlocking {
    val uid = signInStudent()
    seedCounted("full", event().copy(capacity = 2), listOf(uid, OTHER_STUDENT_UID))

    assertEquals(
        CancelRegistrationResult.Cancelled,
        withTimeout(TIMEOUT_MS) { repository.cancel("full") },
    )
    assertFalse(isRegistered("full", uid))
    assertEquals(1L, countOf("full"))

    assertEquals(RegisterResult.Registered, withTimeout(TIMEOUT_MS) { repository.register("full") })
    assertEquals(2L, countOf("full"))
  }

  @Test
  fun cancel_aRegistrationFromBeforeTheCount_isDeletedAndCountsNothing() = runBlocking {
    val uid = signInStudent()
    seed("legacy", event())
    seedRegistration("legacy", uid)

    assertEquals(
        CancelRegistrationResult.Cancelled,
        withTimeout(TIMEOUT_MS) { repository.cancel("legacy") },
    )

    assertFalse(isRegistered("legacy", uid))
    assertNull(countOf("legacy"))
  }

  @Test
  fun cancel_aPrivateEventTheStudentWasRemovedFrom_deletesTheRegistrationAlone() = runBlocking {
    val uid = signInStudent()
    seed("closed", event().copy(isPrivate = true), attendeeCount = 1)
    seedRegistration("closed", uid)

    assertEquals(
        CancelRegistrationResult.Cancelled,
        withTimeout(TIMEOUT_MS) { repository.cancel("closed") },
    )

    assertFalse(isRegistered("closed", uid))
  }

  @Test
  fun severalStudentsAtOnce_allRegister_andAreAllCounted() = runBlocking {
    signInStudent()
    seedCounted("roomy", event())
    val students = signInStudents(StudentApps.COUNT)

    val results =
        withTimeout(BURST_TIMEOUT_MS) {
          coroutineScope { students.map { async { it.register("roomy") } }.awaitAll() }
        }

    assertEquals(List(StudentApps.COUNT) { RegisterResult.Registered }, results)
    assertEquals(StudentApps.COUNT.toLong(), countOf("roomy"))
  }

  @Test
  fun forTheLastPlaces_exactlyThatManyRegister_andTheOthersAreFull() = runBlocking {
    signInStudent()
    // Two places left for five students.
    seedCounted("last", event().copy(capacity = 3), listOf(OTHER_STUDENT_UID))
    val students = signInStudents(StudentApps.COUNT)

    val results =
        withTimeout(BURST_TIMEOUT_MS) {
          coroutineScope { students.map { async { it.register("last") } }.awaitAll() }
        }

    assertEquals("$results", 2, results.count { it == RegisterResult.Registered })
    assertEquals("$results", 3, results.count { it == RegisterResult.Full })
    assertEquals(3L, countOf("last"))
  }

  @Test
  fun anOrganizersSave_whileStudentsRegister_goesThrough() = runBlocking {
    val uid = signInStudent()
    seedCounted("busy", event().withOrganizer(uid))
    val events =
        FirestoreEventRepository(
            db = emulator.firestore,
            auth = EmulatorSignedInUser(emulator),
            profiles = FirestoreUserProfileRepository(emulator.firestore),
            network = AlwaysOnline,
            clock = Clock.fixed(Instant.parse("2030-03-01T12:00:00Z"), ZoneOffset.UTC),
        )
    val students = signInStudents(StudentApps.COUNT)

    val (save, registrations) =
        withTimeout(BURST_TIMEOUT_MS) {
          coroutineScope {
            val registrations = students.map { async { it.register("busy") } }
            val save = async { events.updateEvent(event().copy(id = "busy", title = "Moved")) }
            save.await() to registrations.awaitAll()
          }
        }

    // Each registration rewrites the event; the SDK retries the save they overtake (#373).
    assertEquals(UpdateEventResult.Updated, save)
    assertEquals(List(StudentApps.COUNT) { RegisterResult.Registered }, registrations)
    assertEquals(StudentApps.COUNT.toLong(), countOf("busy"))
  }

  @Test
  fun register_offline_isANetworkError_andNothingIsWrittenLater() = runBlocking {
    val uid = signInStudent()
    seedCounted("public", event())

    // The network check says online, so this is the transaction failing, not being queued.
    val result =
        whileOffline(eventDocument("public")) {
          withTimeout(OFFLINE_TIMEOUT_MS) { repository.register("public") }
        }

    assertEquals(RegisterResult.NetworkError, result)
    assertFalse(isRegistered("public", uid))
    assertEquals(0L, countOf("public"))
  }

  @Test
  fun cancel_offline_isANetworkError_andKeepsTheRegistration() = runBlocking {
    val uid = signInStudent()
    seedCounted("public", event(), listOf(uid))

    val result =
        whileOffline(eventDocument("public")) {
          withTimeout(OFFLINE_TIMEOUT_MS) { repository.cancel("public") }
        }

    assertEquals(CancelRegistrationResult.NetworkError, result)
    assertTrue(isRegistered("public", uid))
    assertEquals(1L, countOf("public"))
  }

  @Test
  fun theRefusalsClassification_offline_isANetworkErrorInsteadOfWaiting() = runBlocking {
    val uid = signInStudent()
    seedCounted("full", event().copy(capacity = 1), listOf(OTHER_STUDENT_UID))

    val result =
        whileOffline(eventDocument("full")) {
          withTimeout(OFFLINE_TIMEOUT_MS) { repository.whyRefused("full", uid) }
        }

    assertEquals(RegisterResult.NetworkError, result)
  }

  /** This event, organized by [uid] alone, like one [uid] created. */
  private fun Event.withOrganizer(uid: String) =
      copy(createdBy = uid, organizerIds = listOf(uid), allowedUids = listOf(uid))

  private fun attendeesOf(eventId: String) = "$EVENTS_COLLECTION/$eventId/$ATTENDEES_COLLECTION"

  private fun eventDocument(eventId: String) =
      emulator.firestore.collection(EVENTS_COLLECTION).document(eventId)

  private companion object {
    /** Fictional accounts and events: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val ORGANIZER_UID = "fictional-organizer"
    const val OTHER_STUDENT_UID = "fictional-student"
    const val SECOND_STUDENT_UID = "fictional-student-2"
    const val TIMEOUT_MS = 10_000L

    /**
     * Several students at once: on the emulator, the refused ones of 30 students for 4 places took
     * up to 10–12 s with the JS SDK (#372's prototype).
     */
    const val BURST_TIMEOUT_MS = 45_000L

    /**
     * Long enough for Firestore to notice the device is offline, after the SDK's attempts: the
     * writes run with its default attempts, about 8 s offline (#119, decision 21).
     */
    const val OFFLINE_TIMEOUT_MS = 60_000L

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
