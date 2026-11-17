// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #370).
package com.polysocial.ui.event.create

import com.polysocial.model.event.OrganizerChanges
import com.polysocial.model.event.validEvent
import com.polysocial.model.user.PublicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The organizers block's pure parts: the rows, the pending changes and who the sheet offers. */
class CoOrganizersStateTest {
  private val stored =
      validEvent()
          .copy(
              id = "event-1",
              createdBy = "creator",
              organizerIds = listOf("creator", "co"),
              allowedUids = listOf("creator", "co"),
          )

  private fun profile(uid: String, name: String) = PublicProfile(uid, name, "IN", "BA3")

  @Test
  fun addingThenRemovingAStudent_leavesNoChange() {
    val changes = OrganizerChanges.NONE.adding("ana").removing("ana")

    assertEquals(OrganizerChanges.NONE, changes)
  }

  @Test
  fun removingThenAddingAnOrganizer_leavesNoChange() {
    val changes = OrganizerChanges.NONE.removing("co").adding("co")

    assertEquals(OrganizerChanges.NONE, changes)
  }

  @Test
  fun organizersAfter_appliesTheChangesInStoredOrder() {
    val changes = OrganizerChanges.NONE.adding("zoe").adding("ana").removing("co")

    assertEquals(listOf("creator", "ana", "zoe"), organizersAfter(stored, changes))
  }

  @Test
  fun organizersAfter_aRefusedChange_showsTheStoredOrganizers() {
    val changes = OrganizerChanges.NONE.removing("creator")

    assertEquals(stored.organizerIds, organizersAfter(stored, changes))
  }

  @Test
  fun entries_markTheCreatorAndYou_andOnlyTheCreatorCantBeRemoved() {
    val lea = profile("co", "Léa Martin")

    val entries = organizerEntries(stored, OrganizerChanges.NONE, "co", mapOf("co" to lea))

    assertEquals(
        listOf(
            OrganizerEntry("creator", null, isCreator = true, isYou = false),
            OrganizerEntry("co", lea, isCreator = false, isYou = true),
        ),
        entries,
    )
    assertFalse(entries[0].canRemove)
    assertTrue(entries[1].canRemove)
  }

  @Test
  fun canAdd_untilFiveOrganizers() {
    val four = List(4) { OrganizerEntry("u$it", null, isCreator = it == 0, isYou = false) }

    assertTrue(CoOrganizersUiState(four).canAdd)
    assertFalse(CoOrganizersUiState(four + OrganizerEntry("u4", null, false, false)).canAdd)
  }

  @Test
  fun addableStudents_leaveOutOrganizersAndStudentsWithoutAProfile_byName() {
    val profiles =
        mapOf(
            "zoe" to profile("zoe", "zoé Rossi"),
            "ana" to profile("ana", "Ana Silva"),
            "co" to profile("co", "Léa Martin"),
        )

    val students =
        addableStudents(
            attendees = listOf("zoe", "co", "ghost", "ana"),
            organizers = listOf("creator", "co"),
            profiles = profiles,
        )

    assertEquals(listOf("ana", "zoe"), students.map { it.uid })
  }
}
