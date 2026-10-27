// Contributors: Claude (fixtures for the association name tests, #128).
package com.polysocial.ui.event

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.CachedAssociationNames
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.Event

// A fictional verified association and a student, for the association name tests (#128): no real
// user data.

const val CLUB_UID = "club-uid"
const val CLUB_NAME = "Test Club"
const val STUDENT_UID = "student-uid"

/** The association [CLUB_UID], named [name]. */
fun club(name: String = CLUB_NAME) =
    Association(
        uid = CLUB_UID,
        name = name,
        type = AssociationType.CLUB,
        about = "A fictional club for tests.",
    )

/** [event] as published by the verified association [uid], with its badge. */
fun associationEvent(event: Event, uid: String = CLUB_UID) =
    event.copy(
        createdBy = uid,
        organizerIds = listOf(uid),
        allowedUids = listOf(uid),
        isAssociationEvent = true,
    )

/** [event] as created by a student, without a badge. */
fun studentEvent(event: Event) =
    event.copy(
        createdBy = STUDENT_UID,
        organizerIds = listOf(STUDENT_UID),
        allowedUids = listOf(STUDENT_UID),
        isAssociationEvent = false,
    )

/**
 * The association repository answering [CLUB_UID] with [club], and the shared names read through
 * it, for a signed-in student: the real cache, so the tests also check each name is read once.
 */
class NamesFixture(answer: AssociationResult = AssociationResult.Found(club())) {
  val associations = FakeAssociationRepository(answer)
  val auth = FakeAuthRepository(AuthUser("viewer-uid", "test.viewer@epfl.ch", true))
  val names = CachedAssociationNames(associations, auth)
}
