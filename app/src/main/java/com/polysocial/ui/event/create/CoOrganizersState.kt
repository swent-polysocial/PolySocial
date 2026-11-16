// Contributors: Claude (Opus 5.5, Anthropic) (Edit event's organizers and the "Add a co-organizer"
// sheet, #370).
package com.polysocial.ui.event.create

import com.polysocial.model.event.Event
import com.polysocial.model.event.MAX_ORGANIZERS
import com.polysocial.model.event.OrganizerChanges
import com.polysocial.model.event.OrganizersUpdate
import com.polysocial.model.event.applyOrganizerChanges
import com.polysocial.model.user.PublicProfile

/**
 * Edit event's "Organizers · n of 5" block (#370, Figma section 17): the organizers the event will
 * have once saved, the creator first, and the "Add a co-organizer" sheet when it is open.
 *
 * @property profilesLoading true while the organizers' public profiles are read, so a row without
 *   one is still loading rather than unavailable.
 */
data class CoOrganizersUiState(
    val organizers: List<OrganizerEntry>,
    val profilesLoading: Boolean = false,
    val sheet: AddCoOrganizerSheet? = null,
) {
  /** Whether "Add a co-organizer" is enabled: an event has at most [MAX_ORGANIZERS] organizers. */
  val canAdd: Boolean
    get() = organizers.size < MAX_ORGANIZERS
}

/**
 * One row of the organizers block.
 *
 * @property profile the organizer's public profile, or null when it is loading or can't be read.
 * @property isYou whether it is the signed-in user, shown as "You".
 */
data class OrganizerEntry(
    val uid: String,
    val profile: PublicProfile?,
    val isCreator: Boolean,
    val isYou: Boolean,
) {
  /** Any organizer removes any co-organizer, themselves included, never the creator (#400). */
  val canRemove: Boolean
    get() = !isCreator
}

/** What the "Add a co-organizer" sheet shows. */
sealed interface AddCoOrganizerSheet {
  /** The students going are being read. */
  data object Loading : AddCoOrganizerSheet

  /** The students going who aren't organizers yet, by name. */
  data class Students(val students: List<PublicProfile>) : AddCoOrganizerSheet

  /** Nobody else is going, so nobody can be added yet. */
  data object NobodyGoing : AddCoOrganizerSheet

  /** The students going couldn't be read (offline or another failure), with Retry. */
  data object Failed : AddCoOrganizerSheet
}

/**
 * The organizers [stored] will have once [changes] are saved, in the order they are stored: the
 * creator first, then co-organizers in the order they were added. A change the repository would
 * refuse can't be made from the block, so it shows the stored organizers.
 */
internal fun organizersAfter(stored: Event, changes: OrganizerChanges): List<String> =
    when (val update = applyOrganizerChanges(stored, changes)) {
      is OrganizersUpdate.Valid -> update.organizerIds
      is OrganizersUpdate.Refused -> stored.organizerIds
    }

/** [changes] with [uid] added: a student removed and added back is simply kept. */
internal fun OrganizerChanges.adding(uid: String): OrganizerChanges =
    if (uid in removed) copy(removed = removed - uid) else copy(added = added + uid)

/** [changes] with [uid] removed: a student added and removed again is simply not added. */
internal fun OrganizerChanges.removing(uid: String): OrganizerChanges =
    if (uid in added) copy(added = added - uid) else copy(removed = removed + uid)

/** The rows of the organizers block of [stored], with [changes] applied, as [me] sees them. */
internal fun organizerEntries(
    stored: Event,
    changes: OrganizerChanges,
    me: String?,
    profiles: Map<String, PublicProfile>,
): List<OrganizerEntry> =
    organizersAfter(stored, changes).map { uid ->
      OrganizerEntry(
          uid = uid,
          profile = profiles[uid],
          isCreator = uid == stored.createdBy,
          isYou = uid == me,
      )
    }

/**
 * The students the sheet offers: those going ([attendees]) who aren't [organizers] or [me], with a
 * public profile, by name. A student without one can't be told apart, so they aren't offered.
 */
internal fun addableStudents(
    attendees: List<String>,
    organizers: List<String>,
    me: String?,
    profiles: Map<String, PublicProfile>,
): List<PublicProfile> =
    (attendees - organizers.toSet() - setOfNotNull(me))
        .mapNotNull(profiles::get)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, PublicProfile::displayName))
