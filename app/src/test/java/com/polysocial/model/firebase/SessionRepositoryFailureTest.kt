// Contributors: OpenAI Codex (blocked cache access reaches typed repository errors, #156).
package com.polysocial.model.firebase

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.*
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRepositoryFailureTest {
  private val profile = UserProfile("fictional", "fictional@epfl.ch", "Test Student", "IN", "BA1")
  private val profiles = FirestoreUserProfileRepository { error("cache cleanup incomplete") }
  private val events =
      FirestoreEventRepository(
          { error("cache cleanup incomplete") },
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
}
