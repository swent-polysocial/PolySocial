// Contributors: Claude (wrote these tests of the fake's getEvent contract, #112; updateEvent and
// deleteEvent, #130; its own association events, #132); Franciszek Najda (reviewed #130).
// Contributors: Claude (Opus 5.5, Anthropic) tested the fake's readable private events, #320.
// Contributors: Claude (Sonnet 5.5, Anthropic) tested the fake's events restricted to sections,
// #369.
// Contributors: Claude (Opus 5.5, Anthropic) tested the fake's organizer changes, #370; read the
// registrations from FakeRegistrationRepository, #370 review.
package com.polysocial.model.event

import com.polysocial.model.registration.FakeRegistrationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that [FakeEventRepository] follows the [EventRepository] contracts of
 * [EventRepository.getEvent], [EventRepository.updateEvent], [EventRepository.deleteEvent],
 * [EventRepository.getOwnAssociationEvents] and [EventRepository.getReadablePrivateEvents], since
 * the ViewModel tests rely on them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeEventRepositoryTest {

  // Events restricted to sections (#369): what the fake shows a student of a given section.

  private fun restricted(id: String, vararg sections: String) =
      validEvent()
          .copy(
              id = id,
              isPrivate = false,
              organizerIds = listOf("organizer"),
              allowedSections = sections.toList(),
          )

  @Test
  fun upcomingEvents_showAStudentTheEventsOpenToEverySectionAndToTheirs() = runTest {
    val repository = FakeEventRepository(currentUid = "student", now = TEST_NOW)
    repository.viewerSection = "IN"
    repository.seed(restricted("open"), restricted("inOnly", "IN"), restricted("maOnly", "MA"))

    val ids =
        (repository.getUpcomingPublicEvents().first() as PublicEventsResult.Events).events.map {
          it.id
        }

    assertEquals(listOf("inOnly", "open"), ids.sorted())
  }

  @Test
  fun upcomingEvents_showAStudentWithoutASectionOnlyTheEventsOpenToEverySection() = runTest {
    val repository = FakeEventRepository(currentUid = "student", now = TEST_NOW)
    repository.seed(restricted("open"), restricted("inOnly", "IN"))

    val ids =
        (repository.getUpcomingPublicEvents().first() as PublicEventsResult.Events).events.map {
          it.id
        }

    assertEquals(listOf("open"), ids)
  }

  @Test
  fun getEvent_isNotFoundForAnotherSection_butFoundForAnOrganizerAnywhere() = runTest {
    val repository = FakeEventRepository(currentUid = "student", now = TEST_NOW)
    repository.viewerSection = "IN"
    repository.seed(restricted("maOnly", "MA"))
    assertEquals(EventResult.NotFound, repository.getEvent("maOnly").first())

    repository.currentUid = "organizer"

    assertTrue(repository.getEvent("maOnly").first() is EventResult.Found)
  }

  private val registrations = FakeRegistrationRepository()
  private val repository = FakeEventRepository(registeredUids = registrations::attendeesOf)
  private val event = validEvent().copy(id = "event-1")

  @Test
  fun getEvent_emitsTheEventThenNotFoundOnceItIsDeleted() = runTest {
    repository.seed(event)
    val results = mutableListOf<EventResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getEvent("event-1").toList(results)
        }

    repository.remove("event-1")
    collecting.cancel()

    assertEquals(listOf(EventResult.Found(event, false), EventResult.NotFound), results)
  }

  @Test
  fun getEvent_offline_isFoundFromTheCacheOrNotCached() = runTest {
    repository.readFromCache = true

    assertEquals(EventResult.NotCached, repository.getEvent("event-1").first())
    repository.seed(event)
    assertEquals(EventResult.Found(event, true), repository.getEvent("event-1").first())
  }

  @Test
  fun getEvent_aDeniedIdIsNotFoundAndEndsEvenIfTheEventExists() = runTest {
    repository.seed(event)
    repository.deniedIds += "event-1"

    assertEquals(listOf(EventResult.NotFound), repository.getEvent("event-1").toList())
  }

  @Test
  fun getEvent_anIdThatCannotBeADocumentIdIsNotFoundEvenOffline() = runTest {
    repository.seed(validEvent())
    repository.readFromCache = true

    for (id in listOf("", " ", "a/b")) {
      assertEquals(id, EventResult.NotFound, repository.getEvent(id).first())
    }
  }

  private val organized = event.withCreator("test-creator", isVerifiedAssociation = false)

  @Test
  fun updateEvent_byAnOrganizer_savesTheNewValuesAndKeepsWhoManagesTheEvent() = runTest {
    repository.seed(organized)
    val edit = organized.copy(title = "Exam review", organizerIds = listOf("intruder"))

    assertEquals(UpdateEventResult.Updated, repository.updateEvent(edit))

    val saved = repository.getEvent("event-1").first() as EventResult.Found
    assertEquals("Exam review", saved.event.title)
    assertEquals(listOf("test-creator"), saved.event.organizerIds)
    assertEquals(listOf(edit), repository.updateRequests)
  }

  @Test
  fun updateEvent_refusesWhatTheRealRepositoryRefuses() = runTest {
    repository.seed(organized, event.copy(id = "event-2"))
    repository.deniedIds += "event-3"

    assertEquals(
        UpdateEventResult.Invalid(listOf(EventValidationError.BLANK_TITLE)),
        repository.updateEvent(organized.copy(title = " ")),
    )
    assertEquals(UpdateEventResult.NotFound, repository.updateEvent(organized.copy(id = "a/b")))
    assertEquals(UpdateEventResult.NotFound, repository.updateEvent(organized.copy(id = "event-3")))
    assertEquals(
        UpdateEventResult.NotOrganizer,
        repository.updateEvent(organized.copy(id = "event-2")),
    )
    repository.updateFailure = UpdateEventResult.NetworkError
    assertEquals(UpdateEventResult.NetworkError, repository.updateEvent(organized))
    repository.currentUid = null
    assertEquals(UpdateEventResult.NotOrganizer, repository.updateEvent(organized))

    assertEquals(listOf(organized, event.copy(id = "event-2")), repository.events)
  }

  @Test
  fun updateEvent_addsOnlyRegisteredStudentsAsOrganizers() = runTest {
    repository.seed(organized)
    registrations.seedRegistration("event-1", "ana")

    assertEquals(
        UpdateEventResult.InvalidOrganizers(OrganizerError.NOT_REGISTERED),
        repository.updateEvent(organized, OrganizerChanges(added = setOf("ana", "ben"))),
    )
    assertEquals(
        UpdateEventResult.Updated,
        repository.updateEvent(organized, OrganizerChanges(added = setOf("ana"))),
    )

    val saved = (repository.getEvent("event-1").first() as EventResult.Found).event
    assertEquals(listOf("test-creator", "ana"), saved.organizerIds)
    assertEquals(listOf("test-creator", "ana"), saved.allowedUids)
    assertEquals(
        listOf(
            OrganizerChanges(added = setOf("ana", "ben")),
            OrganizerChanges(added = setOf("ana")),
        ),
        repository.organizerRequests,
    )
  }

  @Test
  fun updateEvent_refusesOrganizerChangesTheRulesRefuse() = runTest {
    repository.seed(organized)

    assertEquals(
        UpdateEventResult.InvalidOrganizers(OrganizerError.CREATOR_REMOVED),
        repository.updateEvent(organized, OrganizerChanges(removed = setOf("test-creator"))),
    )
  }

  @Test
  fun updateEvent_checksAStartInThePastAgainstTheStoredStart() = runTest {
    val started = organized.copy(startTime = TEST_NOW.minusSeconds(3600))
    repository.seed(started)

    assertEquals(
        UpdateEventResult.Invalid(listOf(EventValidationError.START_IN_PAST)),
        repository.updateEvent(started.copy(startTime = TEST_NOW.minusSeconds(60))),
    )
    assertEquals(listOf(started), repository.events)
    assertEquals(UpdateEventResult.Updated, repository.updateEvent(started.copy(title = "Moved")))
    assertEquals("Moved", repository.events.single().title)
  }

  @Test
  fun deleteEvent_byAnOrganizer_removesTheEvent() = runTest {
    repository.seed(organized)

    assertEquals(DeleteEventResult.Deleted, repository.deleteEvent("event-1"))

    assertEquals(EventResult.NotFound, repository.getEvent("event-1").first())
    assertEquals(listOf("event-1"), repository.deleteRequests)
  }

  @Test
  fun deleteEvent_refusesWhatTheRealRepositoryRefuses() = runTest {
    repository.seed(organized, event.copy(id = "event-2"))

    assertEquals(DeleteEventResult.NotFound, repository.deleteEvent("missing"))
    assertEquals(DeleteEventResult.NotOrganizer, repository.deleteEvent("event-2"))
    repository.deleteFailure = DeleteEventResult.NetworkError
    assertEquals(DeleteEventResult.NetworkError, repository.deleteEvent("event-1"))
    repository.currentUid = null
    assertEquals(DeleteEventResult.NotOrganizer, repository.deleteEvent("event-1"))

    assertEquals(listOf(organized, event.copy(id = "event-2")), repository.events)
  }

  @Test
  fun getOwnAssociationEvents_emitsOnlyTheSignedInAssociationsEventsSortedByStart() = runTest {
    val later = validEvent(startTime = TEST_NOW.plusSeconds(7200)).copy(id = "later")
    val sooner = validEvent(startTime = TEST_NOW.plusSeconds(3600)).copy(id = "sooner")
    val own = { e: Event -> e.withCreator("test-creator", isVerifiedAssociation = true) }
    repository.seed(
        own(later),
        own(sooner),
        event.withCreator("test-creator", isVerifiedAssociation = false),
        own(event).copy(id = "other", createdBy = "other"),
    )

    assertEquals(
        OwnEventsResult.Events(listOf(own(sooner), own(later)), fromCache = false),
        repository.getOwnAssociationEvents().first(),
    )
  }

  @Test
  fun getOwnAssociationEvents_signedOutOrFailing_isAnErrorThatEnds() = runTest {
    repository.ownEventsFailure = true
    assertEquals(listOf(OwnEventsResult.Error), repository.getOwnAssociationEvents().toList())

    repository.ownEventsFailure = false
    repository.currentUid = null
    assertEquals(listOf(OwnEventsResult.Error), repository.getOwnAssociationEvents().toList())
  }

  @Test
  fun getReadablePrivateEvents_emitsOnlyThePrivateEventsListingTheUserSortedByStart() = runTest {
    val reader = "test-creator"
    val later =
        validEvent(startTime = TEST_NOW.plusSeconds(7200))
            .copy(id = "later", isPrivate = true, allowedUids = listOf("other", reader))
    val sooner = later.copy(id = "sooner", startTime = TEST_NOW.plusSeconds(3600))
    repository.seed(
        later,
        sooner,
        later.copy(id = "notAllowed", allowedUids = listOf("other")),
        later.copy(id = "public", isPrivate = false),
    )

    assertEquals(
        PrivateEventsResult.Events(listOf(sooner, later), fromCache = false),
        repository.getReadablePrivateEvents().first(),
    )
  }

  @Test
  fun getReadablePrivateEvents_signedOutOrFailing_isAnErrorThatEnds() = runTest {
    repository.privateEventsFailure = true
    assertEquals(listOf(PrivateEventsResult.Error), repository.getReadablePrivateEvents().toList())

    repository.privateEventsFailure = false
    repository.currentUid = null
    assertEquals(listOf(PrivateEventsResult.Error), repository.getReadablePrivateEvents().toList())
  }
}
