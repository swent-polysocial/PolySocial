// Contributors: OpenAI Codex (GPT-6.2 Sol, high; tested eligibility for #469 under Lucas Pinto
// Ricardo’s supervision and approval).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.ui.event.checkin.CheckInAvailability
import com.polysocial.ui.event.checkin.CheckInEntry
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailCheckInTest {
  @get:Rule val main = MainDispatcherRule()

  private val before = Instant.parse("2026-11-25T18:29:59Z")
  private val event =
      validEvent(startTime = before.plusSeconds(1801), endTime = before.plusSeconds(3601))
          .copy(id = "venue", organizerIds = listOf("organizer"))
  private val student = AuthUser("student", "fixture@epfl.ch", true)
  private val registrations = FakeRegistrationRepository(currentUid = student.uid)
  private val clock =
      object : Clock() {
        override fun instant() = before.plusMillis(main.dispatcher.scheduler.currentTime)

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId) = Clock.fixed(instant(), zone)
      }

  private fun detail(user: AuthUser? = student, event: Event = this.event) =
      EventDetailViewModel(
          SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to event.id)),
          FakeEventRepository().apply { seed(event) },
          FakeNetworkMonitor(),
          FakeAuthRepository(user),
          clock,
          FakeAssociationNames(),
          registrations,
          FakeGroupRepository(),
          FakePublicProfileRepository(),
          FakeReminderScheduler(clock),
      )

  private fun TestScope.observe(
      vm: EventDetailViewModel,
      isStudent: Boolean = true,
  ): List<CheckInEntry?> {
    val values = mutableListOf<CheckInEntry?>()
    backgroundScope.launch { vm.checkInEntry(isStudent).collect { values += it } }
    runCurrent()
    return values
  }

  @Test
  fun registrationEnablesCheckInAndItUpdatesAtBothWindowBoundaries() = runTest {
    val values = observe(detail())
    assertNull(values.last())
    registrations.seedRegistration(event.id, student.uid)
    runCurrent()
    assertEquals(CheckInAvailability.NOT_OPEN, values.last()?.availability)

    advanceTimeBy(1000)
    runCurrent()
    assertEquals(CheckInAvailability.OPEN, values.last()?.availability)
    assertEquals(student.uid, values.last()?.target?.uid)

    advanceTimeBy(3_600_000)
    runCurrent()
    assertEquals(CheckInAvailability.CLOSED, values.last()?.availability)
    registrations.cancel(event.id)
    runCurrent()
    assertNull(values.last())
  }

  @Test
  fun organizersAssociationsUnverifiedAndSignedOutUsersNeverGetTheCheckInEntry() = runTest {
    registrations.seedRegistration(event.id, student.uid)
    val organizerEvent = event.copy(organizerIds = listOf(student.uid))
    assertNull(observe(detail(event = organizerEvent)).last())
    assertNull(observe(detail(), isStudent = false).last())
    assertNull(observe(detail(user = student.copy(isEmailVerified = false))).last())
    assertNull(observe(detail(user = student.copy(email = "fixture@example.org"))).last())
    assertNull(observe(detail(user = null)).last())
  }
}
