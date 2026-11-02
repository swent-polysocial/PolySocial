// Contributors: Claude (Opus 5.5, Anthropic) (drafted the group model, #232).
package com.polysocial.model.group

import java.time.Instant

/** How many students a new group holds: a product decision (#228), not set by the organizer. */
const val GROUP_CAPACITY = 4

/**
 * A small group of students going to the event [eventId] together, stored as
 * `events/{eventId}/groups/{id}`.
 *
 * @property capacity how many students the group holds. Always [GROUP_CAPACITY] today, but stored
 *   with the group so it can change without a migration.
 * @property memberUids the members' UIDs, the creator first, then in the order they joined.
 * @property createdBy the UID of the student who started the group.
 * @property createdAt when the server stored the group, or null while it hasn't confirmed it yet.
 */
data class Group(
    val id: String,
    val eventId: String,
    val capacity: Int,
    val memberUids: List<String>,
    val createdBy: String,
    val createdAt: Instant?,
) {
  /** True when nobody else can join. */
  val isFull: Boolean
    get() = memberUids.size >= capacity
}
