// Contributors: Claude (event repository read and createEvent tests against the Firestore
// emulator, #118). OpenAI Codex (adapted the event-test auth fixture to the verification
// contract, #31); OpenAI Codex (password-reset contract in the event-test fixture, #164); Claude
// (updateEvent and deleteEvent against the rules, #130; an association's own events against the
// rules, #132); Franciszek Najda (reviewed #130).
// OpenAI Codex (Google sign-in contract in the event-test fixture, #106).
// Claude Opus 5.5 (private event detail and offline cache tests, #209).
package com.polysocial.emulator

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.GoogleSignInResult
import com.polysocial.model.auth.LogInResult
import com.polysocial.model.auth.PasswordResetResult
import com.polysocial.model.auth.SendVerificationResult
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.auth.VerificationResult
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.DeleteEventResult
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.FirestoreEventRepository
import com.polysocial.model.event.OwnEventsResult
import com.polysocial.model.event.PublicEventsResult
import com.polysocial.model.event.UpdateEventResult
import com.polysocial.model.event.eventFromFirestore
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.event.withCreator
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.USERS_COLLECTION
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreEventRepository] against the Firestore and Auth emulators, so its transaction,
 * its query, its live listener and the event rules (#47, #52) run on the real Firestore. The read
 * tests seed events as an admin would, from [Event.toFirestoreMap], so the stored field names are
 * the ones the repository writes; the `createEvent` tests check those names by hand. The
 * `updateEvent` and `deleteEvent` tests check that the rules accept what an organizer sends and
 * that each refusal reaches the app as the right result.
 *
 * The clock is fixed at [NOW], far in the future, so the window never depends on the real date.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreEventRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy {
    FirestoreEventRepository(
        db = emulator.firestore,
        auth = SignedInUser(),
        profiles = FirestoreUserProfileRepository(emulator.firestore),
        network = AlwaysOnline,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )
  }

  private suspend fun signInStudent(): String = emulator.signInVerifiedUser(STUDENT_EMAIL).uid

  private suspend fun seed(id: String, event: Event) =
      emulator.createAsAdmin(EVENTS_COLLECTION, id, event.toFirestoreMap())

  /** The first result read from the server (not the cache), failing after [TIMEOUT_MS]. */
  private suspend fun firstServerResult(): PublicEventsResult =
      withTimeout(TIMEOUT_MS) {
        repository.getUpcomingPublicEvents(WINDOW_DAYS).first {
          it !is PublicEventsResult.Events || !it.fromCache
        }
      }

  /**
   * The first answer of [FirestoreEventRepository.getEvent] that doesn't come from the cache: the
   * event from the server, or [EventResult.NotFound]. Fails after [TIMEOUT_MS].
   */
  private suspend fun serverEvent(id: String): EventResult =
      withTimeout(TIMEOUT_MS) {
        repository.getEvent(id).first {
          it is EventResult.NotFound || (it is EventResult.Found && !it.fromCache)
        }
      }

  private fun PublicEventsResult.ids(): List<String> {
    assertTrue("Expected events, got $this", this is PublicEventsResult.Events)
    return (this as PublicEventsResult.Events).events.map { it.id }
  }

  @Test
  fun emitsOnlyPublicEventsInTheWindow_sortedByStartTime() = runBlocking {
    val uid = signInStudent()
    seed("later", publicEvent(startsIn = Duration.ofDays(3)))
    seed("atNow", publicEvent(startsIn = Duration.ZERO))
    seed("sooner", publicEvent(startsIn = Duration.ofHours(2)))
    seed("lastMinute", publicEvent(startsIn = WINDOW.minusMinutes(1)))
    seed("started", publicEvent(startsIn = Duration.ofMinutes(-1)))
    seed("atWindowEnd", publicEvent(startsIn = WINDOW))
    seed("afterWindow", publicEvent(startsIn = WINDOW.plusDays(1)))
    seed("private", privateEvent(uid, startsIn = Duration.ofHours(3)))

    assertEquals(listOf("atNow", "sooner", "later", "lastMinute"), firstServerResult().ids())
  }

  @Test
  fun readsBackEveryStoredField() = runBlocking {
    signInStudent()
    val event =
        publicEvent(startsIn = Duration.ofHours(2))
            .copy(
                endTime = NOW.plus(Duration.ofHours(5)),
                capacity = 40,
                createdBy = ORGANIZER_UID,
                organizerIds = listOf(ORGANIZER_UID),
                allowedUids = listOf(ORGANIZER_UID),
                isAssociationEvent = true,
            )
    seed("full", event)

    val result = firstServerResult()

    assertTrue(result is PublicEventsResult.Events)
    assertEquals(listOf(event.copy(id = "full")), (result as PublicEventsResult.Events).events)
  }

  @Test
  fun emitsAgainWhenAPublicEventIsAdded() =
      runBlocking<Unit> {
        signInStudent()
        seed("first", publicEvent(startsIn = Duration.ofHours(2)))
        val results = Channel<PublicEventsResult>(Channel.UNLIMITED)
        val listener = launch {
          repository.getUpcomingPublicEvents(WINDOW_DAYS).collect(results::send)
        }
        try {
          awaitServerResult(results) { it == listOf("first") }

          seed("second", publicEvent(startsIn = Duration.ofHours(1)))

          awaitServerResult(results) { it == listOf("second", "first") }
        } finally {
          // Removes the snapshot listener, so it can't see the next test's data.
          listener.cancel()
        }
      }

  @Test
  fun neverEmitsAPrivateEvent_evenOneTheStudentMayRead() = runBlocking {
    val uid = signInStudent()
    seed("privateReadable", privateEvent(uid, startsIn = Duration.ofHours(1)))
    val results = Channel<PublicEventsResult>(Channel.UNLIMITED)
    val seen = mutableListOf<List<String>>()
    val listener = launch { repository.getUpcomingPublicEvents(WINDOW_DAYS).collect(results::send) }
    try {
      seen += awaitServerResult(results) { true }

      // Added while listening, then a public event, so the listener has seen the private one.
      seed("privateAddedLive", privateEvent(uid, startsIn = Duration.ofHours(2)))
      seed("public", publicEvent(startsIn = Duration.ofHours(3)))
      seen += awaitServerResult(results) { "public" in it }
    } finally {
      listener.cancel()
    }

    assertEquals(listOf(emptyList<String>(), listOf("public")), seen)
  }

  @Test
  fun getEvent_aPrivateEventIsReadByItsAllowedStudentAndNotFoundForAnyoneElse() = runBlocking {
    val uid = signInStudent()
    seed("privateReadable", privateEvent(uid, startsIn = Duration.ofHours(1)))
    seed("privateOther", privateEvent(MEMBER_UID, startsIn = Duration.ofHours(1)))

    val readable = serverEvent("privateReadable")
    assertTrue("Expected the event, got $readable", readable is EventResult.Found)
    assertEquals("privateReadable", (readable as EventResult.Found).event.id)
    // Denied, it looks exactly like an event that doesn't exist: nothing about it is revealed.
    assertEquals(EventResult.NotFound, serverEvent("privateOther"))
    assertEquals(EventResult.NotFound, serverEvent("missing"))
  }

  @Test
  fun offline_aCachedPrivateEventStaysOutOfThePublicEvents() = runBlocking {
    val uid = signInStudent()
    seed("privateReadable", privateEvent(uid, startsIn = Duration.ofHours(1)))
    seed("public", publicEvent(startsIn = Duration.ofHours(2)))
    // As in the app: the map shows the public events while the student has the private event's
    // detail open. The listeners stay open so the in-memory cache keeps both events.
    val publicResults = Channel<PublicEventsResult>(Channel.UNLIMITED)
    val detailResults = Channel<EventResult>(Channel.UNLIMITED)
    val map = launch {
      repository.getUpcomingPublicEvents(WINDOW_DAYS).collect(publicResults::send)
    }
    val detail = launch { repository.getEvent("privateReadable").collect(detailResults::send) }
    val offline =
        try {
          awaitServerResult(publicResults) { "public" in it }
          withTimeout(TIMEOUT_MS) {
            detailResults.receiveAsFlow().first { it is EventResult.Found && !it.fromCache }
          }
          whileOffline(emulator.firestore.collection(EVENTS_COLLECTION).document("public")) {
            withTimeout(TIMEOUT_MS) { repository.getUpcomingPublicEvents(WINDOW_DAYS).first() }
          }
        } finally {
          map.cancel()
          detail.cancel()
        }

    assertTrue("Expected cached events, got $offline", offline is PublicEventsResult.Events)
    assertTrue((offline as PublicEventsResult.Events).fromCache)
    assertEquals(listOf("public"), offline.ids())
  }

  @Test
  fun skipsAMalformedEvent_andStillEmitsTheOthers() = runBlocking {
    signInStudent()
    seed("valid", publicEvent(startsIn = Duration.ofHours(2)))
    // In the query (public, in the window) but without a title, so it can't be read as an Event.
    emulator.createAsAdmin(
        EVENTS_COLLECTION,
        "noTitle",
        publicEvent(startsIn = Duration.ofHours(1)).toFirestoreMap() - "title",
    )

    assertEquals(listOf("valid"), firstServerResult().ids())
  }

  @Test
  fun signedOut_emitsErrorAndEnds() = runBlocking {
    seed("public", publicEvent(startsIn = Duration.ofHours(2)))

    // The event rules need a verified EPFL account, so the query is denied.
    val results = withTimeout(TIMEOUT_MS) { collectAll() }

    assertEquals(listOf(PublicEventsResult.Error), results)
  }

  @Test
  fun nonEpflAccount_emitsErrorAndEnds() = runBlocking {
    emulator.signInVerifiedUser(ASSOCIATION_EMAIL)
    seed("public", publicEvent(startsIn = Duration.ofHours(2)))

    // A non-EPFL account reads only its own verified-association events, never the public list.
    val results = withTimeout(TIMEOUT_MS) { collectAll() }

    assertEquals(listOf(PublicEventsResult.Error), results)
  }

  @Test
  fun createEvent_storesTheHandWrittenFieldNames() = runBlocking {
    val uid = signInStudent()
    // The form's creator fields are replaced by the signed-in student's (withCreator).
    val event =
        publicEvent(startsIn = Duration.ofHours(2))
            .copy(
                isPrivate = true,
                endTime = NOW.plus(Duration.ofHours(5)),
                capacity = 40,
                locationName = "Test venue",
            )

    val result = withTimeout(TIMEOUT_MS) { repository.createEvent(event) }

    assertTrue("Expected Created, got $result", result is CreateEventResult.Created)
    val id = (result as CreateEventResult.Created).eventId
    val stored =
        emulator.firestore
            .collection(EVENTS_COLLECTION)
            .document(id)
            .get(Source.SERVER)
            .await()
            .data
            .orEmpty()
    // Firestore's automatic mapping would store `private` and `associationEvent` instead, and the
    // queries and rules would never match them.
    assertEquals(STORED_FIELDS, stored.keys)
    assertEquals(true, stored["isPrivate"])
    assertEquals(false, stored["isAssociationEvent"])
    assertEquals(uid, stored["createdBy"])
    assertEquals(listOf(uid), stored["organizerIds"])
    assertEquals(listOf(uid), stored["allowedUids"])
    assertEquals(
        event.withCreator(uid, isVerifiedAssociation = false).copy(id = id),
        eventFromFirestore(id, stored),
    )
  }

  @Test
  fun createEvent_byAVerifiedStudent_isAcceptedAndListedAsUpcoming() = runBlocking {
    signInStudent()

    val result =
        withTimeout(TIMEOUT_MS) {
          repository.createEvent(publicEvent(startsIn = Duration.ofHours(2)))
        }

    assertTrue("Expected Created, got $result", result is CreateEventResult.Created)
    assertEquals(
        listOf((result as CreateEventResult.Created).eventId),
        firstServerResult().ids(),
    )
  }

  @Test
  fun createEvent_deniedByTheRules_isUnexpectedErrorAndWritesNothing() = runBlocking {
    // A verified non-EPFL account with no verified association profile may not create events.
    emulator.signInVerifiedUser(ASSOCIATION_EMAIL)

    val result =
        withTimeout(TIMEOUT_MS) {
          repository.createEvent(publicEvent(startsIn = Duration.ofHours(2)))
        }

    assertEquals(CreateEventResult.UnexpectedError, result)
    emulator.auth.signOut()
    signInStudent()
    assertEquals(emptyList<String>(), firstServerResult().ids())
  }

  /** A public event managed by [organizerUid] and [ORGANIZER_UID], also readable by a member. */
  private fun managedBy(organizerUid: String) =
      publicEvent(startsIn = Duration.ofHours(2))
          .copy(
              createdBy = organizerUid,
              organizerIds = listOf(organizerUid, ORGANIZER_UID),
              allowedUids = listOf(organizerUid, ORGANIZER_UID, MEMBER_UID),
          )

  /** `events/[id]` as the signed-in student reads it from the server. */
  private suspend fun storedEvent(id: String): Event? {
    val snapshot =
        emulator.firestore.collection(EVENTS_COLLECTION).document(id).get(Source.SERVER).await()
    return eventFromFirestore(id, snapshot.data.orEmpty())
  }

  @Test
  fun updateEvent_byAnOrganizer_isAcceptedAndKeepsWhoManagesTheEvent() = runBlocking {
    val uid = signInStudent()
    seed("managed", managedBy(uid))
    // The form also tries to take over the event; the stored creator and organizers stay.
    val edit =
        publicEvent(startsIn = Duration.ofHours(3))
            .copy(
                id = "managed",
                title = "New title",
                createdBy = "intruder",
                organizerIds = listOf("intruder"),
                allowedUids = emptyList(),
            )

    val result = withTimeout(TIMEOUT_MS) { repository.updateEvent(edit) }

    assertEquals(UpdateEventResult.Updated, result)
    val stored = storedEvent("managed")
    assertEquals("New title", stored?.title)
    assertEquals(NOW.plus(Duration.ofHours(3)), stored?.startTime)
    assertEquals(listOf(uid, ORGANIZER_UID), stored?.organizerIds)
    assertEquals(uid, stored?.createdBy)
    assertEquals(listOf(uid, ORGANIZER_UID, MEMBER_UID), stored?.allowedUids)
  }

  @Test
  fun updateEvent_aStartedEvent_isSavedAndKeepsFieldsTheEventDoesNotHold() = runBlocking {
    val uid = signInStudent()
    val started = managedBy(uid).copy(startTime = NOW.minus(Duration.ofHours(1)))
    // A field a later feature may add (for example an attendee count) must survive an edit.
    emulator.createAsAdmin(
        EVENTS_COLLECTION,
        "started",
        started.toFirestoreMap() + ("attendeeCount" to 3L),
    )

    val result =
        withTimeout(TIMEOUT_MS) {
          repository.updateEvent(started.copy(id = "started", title = "New title"))
        }

    assertEquals(UpdateEventResult.Updated, result)
    val snapshot =
        emulator.firestore
            .collection(EVENTS_COLLECTION)
            .document("started")
            .get(Source.SERVER)
            .await()
    assertEquals("New title", snapshot.getString("title"))
    assertEquals(3L, snapshot.getLong("attendeeCount"))
  }

  @Test
  fun updateEvent_bySomeoneWhoIsNotAnOrganizer_isRefusedAndChangesNothing() = runBlocking {
    signInStudent()
    seed("theirs", publicEvent(startsIn = Duration.ofHours(2)))

    val result =
        withTimeout(TIMEOUT_MS) {
          repository.updateEvent(
              publicEvent(startsIn = Duration.ofHours(2)).copy(id = "theirs", title = "Taken over")
          )
        }

    assertEquals(UpdateEventResult.NotOrganizer, result)
    assertEquals("Test event", storedEvent("theirs")?.title)
  }

  @Test
  fun updateEvent_aPrivateEventTheStudentCannotRead_isNotFound() = runBlocking {
    signInStudent()
    seed("hidden", publicEvent(startsIn = Duration.ofHours(2)).copy(isPrivate = true))

    val result =
        withTimeout(TIMEOUT_MS) {
          repository.updateEvent(publicEvent(startsIn = Duration.ofHours(2)).copy(id = "hidden"))
        }

    assertEquals(UpdateEventResult.NotFound, result)
  }

  @Test
  fun deleteEvent_byAnOrganizer_removesTheEvent() = runBlocking {
    val uid = signInStudent()
    seed("managed", managedBy(uid))

    val result = withTimeout(TIMEOUT_MS) { repository.deleteEvent("managed") }

    assertEquals(DeleteEventResult.Deleted, result)
    assertEquals(emptyList<String>(), firstServerResult().ids())
  }

  @Test
  fun deleteEvent_bySomeoneWhoIsNotAnOrganizer_isRefusedAndKeepsTheEvent() = runBlocking {
    signInStudent()
    seed("theirs", publicEvent(startsIn = Duration.ofHours(2)))

    val result = withTimeout(TIMEOUT_MS) { repository.deleteEvent("theirs") }

    assertEquals(DeleteEventResult.NotOrganizer, result)
    assertEquals(listOf("theirs"), firstServerResult().ids())
  }

  @Test
  fun deleteEvent_anEventThatDoesNotExist_isNotFound() = runBlocking {
    signInStudent()

    // The read rule checks the document's fields, so reading a missing event is denied.
    val result = withTimeout(TIMEOUT_MS) { repository.deleteEvent("missing") }

    assertEquals(DeleteEventResult.NotFound, result)
  }

  /** Signs in [ASSOCIATION_EMAIL] with a profile a PolySocial admin has verified. */
  private suspend fun signInVerifiedAssociation(): String {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf(
            "uid" to uid,
            "email" to ASSOCIATION_EMAIL,
            "displayName" to "Test Association",
            "accountType" to "association",
            "isAssociationVerified" to true,
        ),
    )
    return uid
  }

  /**
   * The first result of [FirestoreEventRepository.getOwnAssociationEvents] read from the server.
   */
  private suspend fun firstOwnEvents(): OwnEventsResult =
      withTimeout(TIMEOUT_MS) {
        repository.getOwnAssociationEvents().first {
          it !is OwnEventsResult.Events || !it.fromCache
        }
      }

  @Test
  fun ownEvents_aVerifiedAssociationReadsAllItsOwnEventsAndNoOthers() = runBlocking {
    val uid = signInVerifiedAssociation()
    val own =
        publicEvent(startsIn = Duration.ofDays(2))
            .copy(
                createdBy = uid,
                organizerIds = listOf(uid),
                allowedUids = listOf(uid),
                isAssociationEvent = true,
            )
    seed("later", own)
    seed("privateAndPast", own.copy(isPrivate = true, startTime = NOW.minus(Duration.ofDays(3))))
    seed("someoneElses", publicEvent(startsIn = Duration.ofDays(1)).copy(isAssociationEvent = true))

    val result = firstOwnEvents()

    assertTrue("Expected events, got $result", result is OwnEventsResult.Events)
    assertEquals(
        listOf("privateAndPast", "later"),
        (result as OwnEventsResult.Events).events.map { it.id },
    )
  }

  @Test
  fun ownEvents_aStudentIsDeniedAndGetsAnError() = runBlocking {
    signInStudent()
    seed("public", publicEvent(startsIn = Duration.ofDays(1)))

    assertEquals(OwnEventsResult.Error, firstOwnEvents())
  }

  private suspend fun collectAll(): List<PublicEventsResult> {
    val results = mutableListOf<PublicEventsResult>()
    repository.getUpcomingPublicEvents(WINDOW_DAYS).collect { results += it }
    return results
  }

  /**
   * Reads [results] until a server result whose event IDs match [expected], failing after
   * [TIMEOUT_MS] or on an [PublicEventsResult.Error]. Returns the matching IDs.
   */
  private suspend fun awaitServerResult(
      results: Channel<PublicEventsResult>,
      expected: (List<String>) -> Boolean,
  ): List<String> =
      withTimeout(TIMEOUT_MS) {
        var result: PublicEventsResult.Events
        do {
          val next = results.receive()
          assertFalse("The listener failed", next is PublicEventsResult.Error)
          result = next as PublicEventsResult.Events
        } while (result.fromCache || !expected(result.events.map { it.id }))
        result.events.map { it.id }
      }

  /**
   * The user [FirebaseEmulator] signed in. The repository only reads [currentUser] (in
   * `createEvent`); the tests sign in through the emulator, never through this class.
   */
  private inner class SignedInUser : AuthRepository {
    override suspend fun signInWithGoogle(context: Context): GoogleSignInResult =
        throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

    override suspend fun signUp(fullName: String, email: String, password: String): SignUpResult =
        throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

    override suspend fun logIn(email: String, password: String): LogInResult =
        throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

    override suspend fun sendPasswordResetEmail(email: String): PasswordResetResult =
        throw UnsupportedOperationException("Password recovery is outside this event-test fixture")

    override suspend fun sendVerificationEmail(): SendVerificationResult =
        throw UnsupportedOperationException("Verification is outside this event-test fixture")

    override suspend fun reloadAndCheckVerified(): VerificationResult =
        throw UnsupportedOperationException("Verification is outside this event-test fixture")

    override fun logOut() = emulator.auth.signOut()

    override fun currentUser(): AuthUser? =
        emulator.auth.currentUser?.let { AuthUser(it.uid, it.email.orEmpty(), it.isEmailVerified) }
  }

  private object AlwaysOnline : NetworkMonitor {
    override fun isOnline() = true
  }

  private companion object {
    /** Fictional accounts and events: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val ORGANIZER_UID = "fictional-organizer"
    const val MEMBER_UID = "fictional-member"
    const val WINDOW_DAYS = 14
    val WINDOW: Duration = Duration.ofDays(WINDOW_DAYS.toLong())
    val NOW: Instant = Instant.parse("2030-03-01T12:00:00Z")
    const val TIMEOUT_MS = 10_000L

    /** The field names of `events/{id}`, written out here so a renamed field fails the test. */
    val STORED_FIELDS =
        setOf(
            "title",
            "description",
            "category",
            "location",
            "locationName",
            "startTime",
            "endTime",
            "capacity",
            "isPrivate",
            "createdBy",
            "organizerIds",
            "allowedUids",
            "isAssociationEvent",
        )

    fun publicEvent(startsIn: Duration) =
        Event(
            title = "Test event",
            description = "A fictional event.",
            category = EventCategory.STUDY,
            location = Coordinates(46.5191, 6.5668),
            startTime = NOW.plus(startsIn),
            isPrivate = false,
            createdBy = ORGANIZER_UID,
            organizerIds = listOf(ORGANIZER_UID),
            allowedUids = listOf(ORGANIZER_UID),
        )

    fun privateEvent(readerUid: String, startsIn: Duration) =
        publicEvent(startsIn).copy(isPrivate = true, allowedUids = listOf(ORGANIZER_UID, readerUid))
  }
}
