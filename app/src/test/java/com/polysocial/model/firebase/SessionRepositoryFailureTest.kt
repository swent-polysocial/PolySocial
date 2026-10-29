// Contributors: OpenAI Codex (blocked cache errors and SDK cancellation after rebase, #156).
// Contributors: Claude (Opus 5.5, Anthropic) covered the association repository, event edits and
// the session guard's block, #198; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.firebase

import com.polysocial.model.association.*
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.*
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.*
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryFailureTest {
  private var accessFailure: Exception = IllegalStateException("cache cleanup incomplete")
  private val profile = UserProfile("fictional", "fictional@epfl.ch", "Test Student", "IN", "BA1")
  private val profiles = FirestoreUserProfileRepository { throw accessFailure }
  private val events =
      FirestoreEventRepository(
          { throw accessFailure },
          FakeAuthRepository(AuthUser("fictional", "fictional@epfl.ch", true)),
          FakeUserProfileRepository(),
          object : NetworkMonitor {
            override fun isOnline() = true
          },
          Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC),
      )

  @Test
  fun profileReadsReportBlockedCache() = runTest {
    assertEquals(ProfileResult.UnexpectedError, profiles.getProfile(profile.uid))
  }

  @Test
  fun profileCreationReportsBlockedCache() = runTest {
    assertEquals(CreateProfileResult.UnexpectedError, profiles.createProfile(profile))
  }

  @Test
  fun profileUpdatesReportBlockedCache() = runTest {
    assertEquals(UpdateProfileResult.UnexpectedError, profiles.updateProfile(profile))
  }

  @Test
  fun eventCreationReportsBlockedCache() = runTest {
    val event =
        Event(
            title = "Study",
            description = "",
            category = EventCategory.STUDY,
            location = Coordinates(46.5, 6.6),
            startTime = Instant.parse("2026-10-15T10:00:00Z"),
            isPrivate = false,
        )
    assertEquals(CreateEventResult.UnexpectedError, events.createEvent(event))
  }

  @Test
  fun eventListenerReportsBlockedCacheAndCompletes() = runTest {
    assertEquals(listOf(PublicEventsResult.Error), events.getUpcomingPublicEvents(14).toList())
  }

  @Test
  fun singleEventListenerReportsBlockedCacheAndCompletes() = runTest {
    assertEquals(listOf(EventResult.NotFound), events.getEvent("event-1").toList())
  }

  @Test
  fun cancelledSdkAccessReportsEventFailureWhileCallerRemainsActive() = runTest {
    accessFailure = CancellationException("SDK operation cancelled")
    val event =
        Event(
            title = "Study",
            description = "",
            category = EventCategory.STUDY,
            location = Coordinates(46.5, 6.6),
            startTime = Instant.parse("2026-10-15T10:00:00Z"),
            isPrivate = false,
        )
    assertEquals(CreateEventResult.UnexpectedError, events.createEvent(event))
    assertTrue(currentCoroutineContext().isActive)
  }

  @Test
  fun cancelledSdkAccessReportsListenerFailureWhileCallerRemainsActive() = runTest {
    accessFailure = CancellationException("SDK operation cancelled")
    assertEquals(listOf(PublicEventsResult.Error), events.getUpcomingPublicEvents(14).toList())
    assertTrue(currentCoroutineContext().isActive)
  }

  // ---- #198: every repository the session guard can block ----

  private val associations = FirestoreAssociationRepository { throw accessFailure }
  private val association = Association("fictional", "Test Club", AssociationType.CLUB, "About.")
  private val study =
      Event(
          id = "event-1",
          title = "Study",
          description = "",
          category = EventCategory.STUDY,
          location = Coordinates(46.5, 6.6),
          startTime = Instant.parse("2026-10-15T10:00:00Z"),
          isPrivate = false,
      )

  @Test
  fun associationReadsReportBlockedCache() = runTest {
    assertEquals(AssociationResult.UnexpectedError, associations.getAssociation("fictional"))
  }

  @Test
  fun associationCreationReportsBlockedCache() = runTest {
    assertEquals(
        CreateAssociationResult.UnexpectedError,
        associations.createAssociation(association),
    )
  }

  @Test
  fun associationUpdatesReportBlockedCache() = runTest {
    assertEquals(
        UpdateAssociationResult.UnexpectedError,
        associations.updateAssociation(association),
    )
  }

  @Test
  fun eventUpdatesReportBlockedCache() = runTest {
    assertEquals(UpdateEventResult.UnexpectedError, events.updateEvent(study))
  }

  @Test
  fun eventDeletionReportsBlockedCache() = runTest {
    assertEquals(DeleteEventResult.UnexpectedError, events.deleteEvent("event-1"))
  }

  @Test
  fun ownEventsListenerReportsBlockedCacheAndCompletes() = runTest {
    assertEquals(listOf(OwnEventsResult.Error), events.getOwnAssociationEvents().toList())
  }

  @Test
  fun sessionBlockedByTheGuard_failsThroughTheTypedErrors() = runTest {
    // What the session guard does when another account signs in, before its clear finishes.
    val session = FirestoreSession { mockk() }.apply { blockUnlessEmpty() }
    val guarded = FirestoreUserProfileRepository { session.database }

    assertEquals(ProfileResult.UnexpectedError, guarded.getProfile(profile.uid))
    assertEquals(
        AssociationResult.UnexpectedError,
        FirestoreAssociationRepository { session.database }.getAssociation("fictional"),
    )
  }
}
