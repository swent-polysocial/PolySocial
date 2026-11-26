// Contributors: Claude (wrote these tests); Mohamed Khellaf (reviewed).
// Contributors: Claude (Opus 5.5, Anthropic) (the attendee count and when an event is full, #373).
// Contributors: Claude (Sonnet 5.5, Anthropic) (the sections an event is open to, #369).
package com.polysocial.model.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTest {

  @Test
  fun anEventWithoutSections_isOpenToEverySection() {
    assertTrue(validEvent().isOpenToAllSections())
    assertFalse(validEvent().copy(allowedSections = listOf("IN")).isOpenToAllSections())
  }

  @Test
  fun withCreator_keepsTheSectionsOfAPublicStudentEvent_inOrder_withoutDuplicatesOrUnknownCodes() {
    val event =
        validEvent()
            .copy(isPrivate = false, allowedSections = listOf("SC", "IN", "IN", "XX"))
            .withCreator("creator", isVerifiedAssociation = false)

    assertEquals(listOf("IN", "SC"), event.allowedSections)
  }

  @Test
  fun withCreator_dropsTheSectionsOfAPrivateEventAndOfAnAssociations() {
    val restricted = validEvent().copy(allowedSections = listOf("IN"))

    assertEquals(
        emptyList<String>(),
        restricted.copy(isPrivate = true).withCreator("creator", false).allowedSections,
    )
    assertEquals(
        emptyList<String>(),
        restricted.copy(isPrivate = false).withCreator("club", true).allowedSections,
    )
  }

  @Test
  fun withOwnershipOf_keepsTheStoredSections_notTheOnesTheFormLoaded() {
    // The form loaded [IN, MA]; a co-organizer has narrowed the stored event to [IN] since.
    val stored =
        validEvent().copy(isPrivate = false, allowedSections = listOf("IN")).withCreator("c", false)
    val fromAStaleForm = stored.copy(title = "Exam review", allowedSections = listOf("IN", "MA"))

    val saved = fromAStaleForm.withOwnershipOf(stored)

    assertEquals(listOf("IN"), saved.allowedSections)
    assertEquals("Exam review", saved.title)
  }

  @Test
  fun withOwnershipOf_withSectionsEdited_writesTheNewSections() {
    // The picker changed the stored [IN] to [IN, MA]; the explicit edit must land.
    val stored =
        validEvent().copy(isPrivate = false, allowedSections = listOf("IN")).withCreator("c", false)
    val picked = stored.copy(allowedSections = listOf("IN", "MA"))

    val saved = picked.withOwnershipOf(stored, sectionsEdited = true)

    assertEquals(listOf("IN", "MA"), saved.allowedSections)
  }

  @Test
  fun withOwnershipOf_neverGivesSectionsToAnAssociationsEvent() {
    val stored = validEvent().copy(isPrivate = false).withCreator("creator", false)
    val edited = stored.copy(allowedSections = listOf("MA", "IN"))
    val association = stored.copy(isAssociationEvent = true)

    assertEquals(emptyList<String>(), edited.withOwnershipOf(association).allowedSections)
  }

  @Test
  fun aPrivateEventIsMadeOpenToEverySectionWhenSaved() {
    val stored = validEvent().copy(isPrivate = false).withCreator("creator", false)

    val edited = stored.copy(isPrivate = true, allowedSections = listOf("IN"))

    assertEquals(emptyList<String>(), edited.withOwnershipOf(stored).allowedSections)
  }

  @Test
  fun withCreator_makesTheCreatorOrganizerAndAllowedReader() {
    val event = validEvent().withCreator("creator", isVerifiedAssociation = false)

    assertEquals("creator", event.createdBy)
    assertEquals(listOf("creator"), event.organizerIds)
    assertEquals(listOf("creator"), event.allowedUids)
  }

  @Test
  fun withCreator_replacesACreatorSetByTheCaller() {
    val event =
        validEvent()
            .copy(createdBy = "someone-else")
            .withCreator("creator", isVerifiedAssociation = false)

    assertEquals("creator", event.createdBy)
    assertEquals(listOf("creator"), event.organizerIds)
  }

  @Test
  fun withCreator_dropsOrganizersAndReadersSetByTheCaller() {
    val event =
        validEvent()
            .copy(organizerIds = listOf("someone-else"), allowedUids = listOf("intruder"))
            .withCreator("creator", isVerifiedAssociation = false)

    assertEquals(listOf("creator"), event.organizerIds)
    assertEquals(listOf("creator"), event.allowedUids)
  }

  @Test
  fun withCreator_givesTheBadgeOnlyToAVerifiedAssociation() {
    val claimedBadge = validEvent().copy(isAssociationEvent = true)

    assertFalse(
        claimedBadge.withCreator("student", isVerifiedAssociation = false).isAssociationEvent
    )
    assertTrue(
        validEvent().withCreator("association", isVerifiedAssociation = true).isAssociationEvent
    )
  }

  @Test
  fun withCreator_leavesTheEventDetailsUnchanged() {
    val original = validEvent()
    val created = original.withCreator("creator", isVerifiedAssociation = true)

    assertEquals(
        original,
        created.copy(
            createdBy = "",
            organizerIds = emptyList(),
            allowedUids = emptyList(),
            isAssociationEvent = false,
        ),
    )
  }

  @Test
  fun withCreator_startsTheCountAtZero() {
    assertEquals(
        0,
        validEvent().copy(attendeeCount = 12).withCreator("creator", false).attendeeCount,
    )
  }

  @Test
  fun withOwnershipOf_keepsTheStoredCount() {
    val stored = validEvent().copy(id = "event-1", attendeeCount = 5)

    assertEquals(5, validEvent().copy(attendeeCount = 0).withOwnershipOf(stored).attendeeCount)
  }

  @Test
  fun isFull_onceTheCountReachesTheCapacity() {
    assertFalse(validEvent(capacity = 8).copy(attendeeCount = 7).isFull())
    assertTrue(validEvent(capacity = 8).copy(attendeeCount = 8).isFull())
  }

  @Test
  fun isFull_whenTheCapacityWasLoweredBelowTheCount() {
    assertTrue(validEvent(capacity = 2).copy(attendeeCount = 5).isFull())
  }

  @Test
  fun isFull_neverWithoutACapacity() {
    assertFalse(validEvent(capacity = null).copy(attendeeCount = 1000).isFull())
  }

  @Test
  fun isFull_withACapacityOfZero_evenWithNobodyGoing() {
    // The form asks for at least 2, but the rules don't, and they refuse every registration.
    assertTrue(validEvent(capacity = 0).isFull())
  }
}
