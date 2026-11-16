// Contributors: Claude (Opus 5.5, Anthropic) (drafted applying co-organizer changes, #370).
package com.polysocial.model.event

/** The most organizers an event has, its creator included (decision 17). */
const val MAX_ORGANIZERS = 5

/**
 * Co-organizers an organizer added or removed on Edit event, saved with the rest of the event.
 *
 * The form sends what changed rather than the whole list, so two organizers editing at once don't
 * undo each other's changes: see [applyOrganizerChanges].
 */
data class OrganizerChanges(
    val added: Set<String> = emptySet(),
    val removed: Set<String> = emptySet(),
) {
  companion object {
    /** No change: the stored organizers are kept. */
    val NONE = OrganizerChanges()
  }
}

/** Why an [OrganizerChanges] was refused. Nothing was written. */
enum class OrganizerError {
  /** The creator can't be removed. */
  CREATOR_REMOVED,

  /** The event would have more than [MAX_ORGANIZERS] organizers. */
  TOO_MANY_ORGANIZERS,

  /** An association event keeps its single organizer until the team decides (decision 17). */
  ASSOCIATION_EVENT,

  /** A student added isn't registered for the event (any more). */
  NOT_REGISTERED,
}

/** Outcome of [applyOrganizerChanges]. */
sealed interface OrganizersUpdate {
  /**
   * The event's new [organizerIds] and [allowedUids]. [newOrganizers], the ones not organizers yet,
   * must still be registered for the event, which only the repository can check.
   */
  data class Valid(
      val organizerIds: List<String>,
      val allowedUids: List<String>,
      val newOrganizers: List<String>,
  ) : OrganizersUpdate

  /** The changes break [error]'s rule. */
  data class Refused(val error: OrganizerError) : OrganizersUpdate
}

/**
 * Applies [changes] to the organizers of [stored], the event as it is stored now, following the
 * Security Rules (#370): the creator stays, at most [MAX_ORGANIZERS] organizers, and an association
 * event's organizers don't change.
 *
 * Adding an organizer who already is one, or removing someone who isn't, changes nothing, so a
 * change another organizer already saved isn't an error. A UID both added and removed is removed.
 * The stored order is kept and new organizers come last. Each new organizer is also added to
 * [Event.allowedUids], so every organizer can read the event; a removed one stays there, since they
 * are still registered and a private event's attendees must keep reading it.
 */
fun applyOrganizerChanges(stored: Event, changes: OrganizerChanges): OrganizersUpdate {
  if (stored.createdBy in changes.removed) {
    return OrganizersUpdate.Refused(OrganizerError.CREATOR_REMOVED)
  }
  val newOrganizers =
      changes.added.filter { it !in stored.organizerIds && it !in changes.removed }.sorted()
  val organizerIds = stored.organizerIds.filter { it !in changes.removed } + newOrganizers
  if (organizerIds != stored.organizerIds) {
    if (stored.isAssociationEvent) return OrganizersUpdate.Refused(OrganizerError.ASSOCIATION_EVENT)
    if (organizerIds.size > MAX_ORGANIZERS) {
      return OrganizersUpdate.Refused(OrganizerError.TOO_MANY_ORGANIZERS)
    }
  }
  val allowedUids = stored.allowedUids + newOrganizers.filter { it !in stored.allowedUids }
  return OrganizersUpdate.Valid(organizerIds, allowedUids, newOrganizers)
}
