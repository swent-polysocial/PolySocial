// Contributors: Claude Opus 5.5 (medium) (chosen events against the rules, #203).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FirestoreEventRepository
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.registration.ATTENDEES_COLLECTION
import com.polysocial.model.registration.ChosenEventsRepository
import com.polysocial.model.registration.ChosenEventsResult
import com.polysocial.model.registration.FirestoreRegistrationRepository
import com.polysocial.model.user.FirestoreUserProfileRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [ChosenEventsRepository] on the real Firestore repositories against the Firestore and Auth
 * emulators, so the registrations query and the event reads run under the real Security Rules.
 * Events and registrations are seeded as an admin would, bypassing the rules.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class ChosenEventsEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy {
    val auth = EmulatorSignedInUser(emulator)
    ChosenEventsRepository(
        FirestoreRegistrationRepository(
            db = emulator.firestore,
            auth = auth,
            network = AlwaysOnline,
        ),
        FirestoreEventRepository(
            db = emulator.firestore,
            auth = auth,
            profiles = FirestoreUserProfileRepository(emulator.firestore),
            network = AlwaysOnline,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        ),
    )
  }

  private suspend fun seed(id: String, event: Event) =
      emulator.createAsAdmin(EVENTS_COLLECTION, id, event.toFirestoreMap())

  private suspend fun seedRegistration(eventId: String, uid: String) =
      emulator.createAsAdmin(
          "$EVENTS_COLLECTION/$eventId/$ATTENDEES_COLLECTION",
          uid,
          mapOf("uid" to uid, "registeredAt" to NOW),
      )

  /** The first result where every registered event has answered from the server. */
  private suspend fun settled(): ChosenEventsResult.Events =
      withTimeout(TIMEOUT_MS) {
        val result =
            repository.observeChosenEvents().first {
              it !is ChosenEventsResult.Events || (!it.fromCache && it.pendingIds.isEmpty())
            }
        assertTrue("Expected events, got $result", result is ChosenEventsResult.Events)
        result as ChosenEventsResult.Events
      }

  @Test
  fun theStudentsChosenEvents_includingFarAheadAndAllowedPrivateOnes_inStartOrder() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seed("far", event(Instant.parse("2030-09-01T18:00:00Z")))
    seed("soon", event(Instant.parse("2030-03-02T18:00:00Z")))
    seed("private", event(Instant.parse("2030-04-01T18:00:00Z")).allowing(uid))
    listOf("far", "soon", "private").forEach { seedRegistration(it, uid) }

    assertEquals(listOf("soon", "private", "far"), settled().events.map { it.id })
  }

  @Test
  fun anotherStudentsRegistrations_areNeverListed() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seed("mine", event(Instant.parse("2030-03-02T18:00:00Z")))
    seed("theirs", event(Instant.parse("2030-03-03T18:00:00Z")))
    seedRegistration("mine", uid)
    seedRegistration("theirs", OTHER_STUDENT_UID)

    val result = settled()

    assertEquals(listOf("mine"), result.events.map { it.id })
    assertEquals(emptySet<String>(), result.unavailableIds)
  }

  @Test
  fun aDeletedOrNoLongerReadableEvent_isUnavailable_withoutItsDetails() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seed("readable", event(Instant.parse("2030-03-02T18:00:00Z")))
    // A private event the student isn't allowed in, and one that was deleted.
    seed("private", event(Instant.parse("2030-03-03T18:00:00Z")).copy(isPrivate = true))
    listOf("readable", "private", "deleted").forEach { seedRegistration(it, uid) }

    val result = settled()

    assertEquals(listOf("readable"), result.events.map { it.id })
    assertEquals(setOf("private", "deleted"), result.unavailableIds)
  }

  private fun Event.allowing(uid: String) = copy(isPrivate = true, allowedUids = allowedUids + uid)

  private companion object {
    /** Fictional accounts and events: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val ORGANIZER_UID = "fictional-organizer"
    const val OTHER_STUDENT_UID = "fictional-student"
    const val TIMEOUT_MS = 15_000L
    val NOW: Instant = Instant.parse("2030-02-01T12:00:00Z")

    fun event(start: Instant) =
        Event(
            title = "Test event",
            description = "A fictional event.",
            category = EventCategory.STUDY,
            location = Coordinates(46.5191, 6.5668),
            startTime = start,
            isPrivate = false,
            createdBy = ORGANIZER_UID,
            organizerIds = listOf(ORGANIZER_UID),
            allowedUids = listOf(ORGANIZER_UID),
        )
  }
}
