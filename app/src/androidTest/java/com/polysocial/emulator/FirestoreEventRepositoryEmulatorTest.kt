// Contributors: Claude (event repository read tests against the Firestore emulator, #118).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.LogInResult
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FirestoreEventRepository
import com.polysocial.model.event.PublicEventsResult
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.FirestoreUserProfileRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreEventRepository.getUpcomingPublicEvents] against the Firestore and Auth
 * emulators, so its query, its live listener and the event read rules (#52) run on the real
 * Firestore. Events are written as an admin would, from [Event.toFirestoreMap], so the stored field
 * names are the ones the repository writes.
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
    override suspend fun signUp(fullName: String, email: String, password: String): SignUpResult =
        throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

    override suspend fun logIn(email: String, password: String): LogInResult =
        throw UnsupportedOperationException("Sign in with FirebaseEmulator.signInVerifiedUser")

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
    const val WINDOW_DAYS = 14
    val WINDOW: Duration = Duration.ofDays(WINDOW_DAYS.toLong())
    val NOW: Instant = Instant.parse("2030-03-01T12:00:00Z")
    const val TIMEOUT_MS = 10_000L

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
