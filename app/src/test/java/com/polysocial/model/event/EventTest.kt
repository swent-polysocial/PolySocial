// Contributors: Claude (wrote these tests).
package com.polysocial.model.event

import org.junit.Assert.assertEquals
import org.junit.Test

class EventTest {

  @Test
  fun withCreator_makesTheCreatorOrganizerAndAllowedReader() {
    val event = validEvent().withCreator("creator")

    assertEquals("creator", event.createdBy)
    assertEquals(listOf("creator"), event.organizerIds)
    assertEquals(listOf("creator"), event.allowedUids)
  }

  @Test
  fun withCreator_replacesACreatorSetByTheCaller() {
    val event = validEvent().copy(createdBy = "someone-else").withCreator("creator")

    assertEquals("creator", event.createdBy)
    assertEquals(listOf("creator"), event.organizerIds)
  }

  @Test
  fun withCreator_keepsCoOrganizersAndLetsThemReadTheEvent() {
    val event =
        validEvent()
            .copy(organizerIds = listOf("co-organizer"), allowedUids = listOf("member"))
            .withCreator("creator")

    assertEquals(listOf("creator", "co-organizer"), event.organizerIds)
    assertEquals(listOf("creator", "co-organizer", "member"), event.allowedUids)
  }

  @Test
  fun withCreator_doesNotListTheCreatorTwice() {
    val event =
        validEvent()
            .copy(organizerIds = listOf("creator"), allowedUids = listOf("creator"))
            .withCreator("creator")

    assertEquals(listOf("creator"), event.organizerIds)
    assertEquals(listOf("creator"), event.allowedUids)
  }

  @Test
  fun withCreator_leavesTheEventDetailsUnchanged() {
    val original = validEvent()
    val created = original.withCreator("creator")

    assertEquals(
        original,
        created.copy(createdBy = "", organizerIds = emptyList(), allowedUids = emptyList()),
    )
  }
}
