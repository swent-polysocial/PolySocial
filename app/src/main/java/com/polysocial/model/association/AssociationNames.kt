// Contributors: Claude (association names shared by the event screens, #128).
package com.polysocial.model.association

import kotlinx.coroutines.flow.Flow

/**
 * The names of the associations whose events the app shows (#128): the Events tab's cards, the
 * map's preview and the event detail. Each name is read once with [AssociationRepository] and then
 * reused by every screen, instead of once per card.
 *
 * Only a name that was read is kept: a name that can't be read (offline and not cached, not
 * readable, missing) stays unknown, and the next [load] tries again. The names belong to the
 * signed-in account: when another account signs in, the names the previous one read are dropped, so
 * a student never sees a name another account was allowed to read.
 */
interface AssociationNames {
  /** The names read so far for the signed-in account, by association UID. */
  val names: Flow<Map<String, String>>

  /**
   * Reads the name of the association [uid], unless it is already known. Several calls at once for
   * the same association read it only once. A failure leaves the name unknown and is not reported:
   * the screens then show the badge without a name.
   */
  suspend fun load(uid: String)
}
