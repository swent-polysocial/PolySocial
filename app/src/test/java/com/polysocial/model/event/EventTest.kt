// Contributors: Claude (wrote these tests).
package com.polysocial.model.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTest {

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
}
