// Contributors: Claude (fixtures shared by the organizer row tests, #134).
package com.polysocial.ui.event.create

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationType
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.UserProfile

// A fictional account and its association, for the organizer row tests (#134): no real user data.

const val ORGANIZER_UID = "club-uid"
const val ORGANIZER_EMAIL = "contact@club.example.org"

val TEST_CLUB =
    Association(
        uid = ORGANIZER_UID,
        name = "Test Club",
        type = AssociationType.CLUB,
        about = "A fictional club for tests.",
    )

/** A student's profile for [ORGANIZER_UID]. */
fun studentProfile() =
    UserProfile(
        uid = ORGANIZER_UID,
        email = "test.student@epfl.ch",
        displayName = "Test Student",
        section = "IN",
        year = "BA3",
    )

/** The profile of [ORGANIZER_UID] as an association a PolySocial admin has verified. */
fun verifiedAssociationProfile() =
    UserProfile(
        uid = ORGANIZER_UID,
        email = ORGANIZER_EMAIL,
        displayName = "Test Club",
        section = "",
        year = "",
        accountType = AccountType.ASSOCIATION,
        isAssociationVerified = true,
    )
