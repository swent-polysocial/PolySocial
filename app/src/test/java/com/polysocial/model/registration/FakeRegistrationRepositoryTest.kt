// Contributors: Claude Opus 5.5 (wrote these tests, #121).
package com.polysocial.model.registration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The fake follows the [RegistrationRepository] contracts the screens will be tested against. */
class FakeRegistrationRepositoryTest {
  private val repository =
      FakeRegistrationRepository(currentUid = "student").apply { readableEventIds += "event-1" }

  private suspend fun myRegistrations() = repository.observeMyRegistrations().first()

  @Test
  fun register_thenCancel_updatesTheStudentsRegistrations() = runTest {
    assertEquals(RegisterResult.Registered, repository.register("event-1"))
    assertEquals(MyRegistrationsResult.Registrations(setOf("event-1"), false), myRegistrations())

    assertEquals(CancelRegistrationResult.Cancelled, repository.cancel("event-1"))
    assertEquals(MyRegistrationsResult.Registrations(emptySet(), false), myRegistrations())
  }

  @Test
  fun registeringOrCancellingTwice_changesNothing() = runTest {
    repository.register("event-1")
    assertEquals(RegisterResult.Registered, repository.register("event-1"))
    assertEquals(setOf("student"), repository.attendeesOf("event-1"))

    repository.cancel("event-1")
    assertEquals(CancelRegistrationResult.Cancelled, repository.cancel("event-1"))
    assertEquals(emptySet<String>(), repository.attendeesOf("event-1"))
  }

  @Test
  fun anEventTheStudentCannotRead_isNotFound() = runTest {
    assertEquals(RegisterResult.NotFound, repository.register("private"))

    assertEquals(emptySet<String>(), repository.attendeesOf("private"))
  }

  @Test
  fun nobodySignedIn_changesNothingAndCannotObserve() = runTest {
    repository.currentUid = null

    assertEquals(RegisterResult.NotSignedIn, repository.register("event-1"))
    assertEquals(CancelRegistrationResult.NotSignedIn, repository.cancel("event-1"))
    assertEquals(MyRegistrationsResult.Error, myRegistrations())
  }

  @Test
  fun failures_areReturnedWithoutChangingAnything() = runTest {
    repository.registerFailure = RegisterResult.NetworkError
    assertEquals(RegisterResult.NetworkError, repository.register("event-1"))
    assertEquals(emptySet<String>(), repository.attendeesOf("event-1"))

    repository.registerFailure = null
    repository.register("event-1")
    repository.cancelFailure = CancelRegistrationResult.UnexpectedError
    assertEquals(CancelRegistrationResult.UnexpectedError, repository.cancel("event-1"))
    assertEquals(setOf("student"), repository.attendeesOf("event-1"))
  }

  @Test
  fun observing_reportsOnlyTheStudentsOwnRegistrationsAndTheCache() = runTest {
    repository.readableEventIds += "event-2"
    repository.register("event-1")
    repository.currentUid = "other-student"
    repository.register("event-2")
    repository.currentUid = "student"
    repository.readFromCache = true

    assertEquals(MyRegistrationsResult.Registrations(setOf("event-1"), true), myRegistrations())

    repository.readFailure = true
    assertEquals(MyRegistrationsResult.Error, myRegistrations())
  }
}
