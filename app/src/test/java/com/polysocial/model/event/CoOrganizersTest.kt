// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #370).
package com.polysocial.model.event

import org.junit.Assert.assertEquals
import org.junit.Test

class CoOrganizersTest {
  /** A student's event, made by "creator", co-organized by "co" and readable by "member" too. */
  private val stored =
      validEvent()
          .copy(
              id = "event-1",
              createdBy = "creator",
              organizerIds = listOf("creator", "co"),
              allowedUids = listOf("creator", "co", "member"),
          )

  private fun valid(organizerIds: List<String>, allowedUids: List<String>, new: List<String>) =
      OrganizersUpdate.Valid(organizerIds, allowedUids, new)

  @Test
  fun noChanges_keepTheStoredOrganizersAndReaders() {
    assertEquals(
        valid(stored.organizerIds, stored.allowedUids, emptyList()),
        applyOrganizerChanges(stored, OrganizerChanges.NONE),
    )
  }

  @Test
  fun addedStudents_comeLastSortedAndBecomeReaders() {
    val update = applyOrganizerChanges(stored, OrganizerChanges(added = setOf("zoe", "ana")))

    assertEquals(
        valid(
            listOf("creator", "co", "ana", "zoe"),
            listOf("creator", "co", "member", "ana", "zoe"),
            listOf("ana", "zoe"),
        ),
        update,
    )
  }

  @Test
  fun anAddedReader_isNotListedTwiceAsReader() {
    val update = applyOrganizerChanges(stored, OrganizerChanges(added = setOf("member")))

    assertEquals(
        valid(listOf("creator", "co", "member"), stored.allowedUids, listOf("member")),
        update,
    )
  }

  @Test
  fun aRemovedCoOrganizer_staysAReader() {
    val update = applyOrganizerChanges(stored, OrganizerChanges(removed = setOf("co")))

    assertEquals(valid(listOf("creator"), stored.allowedUids, emptyList()), update)
  }

  @Test
  fun changesAnotherOrganizerAlreadySaved_changeNothing() {
    val update =
        applyOrganizerChanges(
            stored,
            OrganizerChanges(added = setOf("co"), removed = setOf("someone-removed")),
        )

    assertEquals(valid(stored.organizerIds, stored.allowedUids, emptyList()), update)
  }

  @Test
  fun aStudentBothAddedAndRemoved_isRemoved() {
    val update =
        applyOrganizerChanges(
            stored,
            OrganizerChanges(added = setOf("ana", "co"), removed = setOf("ana", "co")),
        )

    assertEquals(valid(listOf("creator"), stored.allowedUids, emptyList()), update)
  }

  @Test
  fun removingTheCreator_isRefused() {
    assertEquals(
        OrganizersUpdate.Refused(OrganizerError.CREATOR_REMOVED),
        applyOrganizerChanges(stored, OrganizerChanges(removed = setOf("creator"))),
    )
  }

  @Test
  fun fiveOrganizers_areAllowedButNotSix() {
    assertEquals(
        valid(
            listOf("creator", "co", "a", "b", "c"),
            listOf("creator", "co", "member", "a", "b", "c"),
            listOf("a", "b", "c"),
        ),
        applyOrganizerChanges(stored, OrganizerChanges(added = setOf("a", "b", "c"))),
    )
    assertEquals(
        OrganizersUpdate.Refused(OrganizerError.TOO_MANY_ORGANIZERS),
        applyOrganizerChanges(stored, OrganizerChanges(added = setOf("a", "b", "c", "d"))),
    )
  }

  @Test
  fun removingOneMakesRoomForAnother_inTheSameChange() {
    val full =
        stored.copy(
            organizerIds = listOf("creator", "co", "a", "b", "c"),
            allowedUids = listOf("creator", "co", "member", "a", "b", "c"),
        )

    val update =
        applyOrganizerChanges(full, OrganizerChanges(added = setOf("d"), removed = setOf("a")))

    assertEquals(
        valid(
            listOf("creator", "co", "b", "c", "d"),
            full.allowedUids + "d",
            listOf("d"),
        ),
        update,
    )
  }

  @Test
  fun anAssociationEventsOrganizers_cantChange() {
    val association = stored.copy(isAssociationEvent = true)

    for (changes in
        listOf(OrganizerChanges(added = setOf("ana")), OrganizerChanges(removed = setOf("co")))) {
      assertEquals(
          OrganizersUpdate.Refused(OrganizerError.ASSOCIATION_EVENT),
          applyOrganizerChanges(association, changes),
      )
    }
  }

  @Test
  fun anAssociationEventsUnchangedOrganizers_areValid() {
    val association = stored.copy(isAssociationEvent = true)

    assertEquals(
        valid(stored.organizerIds, stored.allowedUids, emptyList()),
        applyOrganizerChanges(association, OrganizerChanges(added = setOf("co"))),
    )
  }
}
