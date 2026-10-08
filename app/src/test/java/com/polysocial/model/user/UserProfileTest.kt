// Contributors: Claude (wrote these tests, #45).
package com.polysocial.model.user

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {
  private val student =
      UserProfile(
          uid = "u1",
          email = "a@epfl.ch",
          displayName = "Test Student",
          section = "IN",
          year = "BA3",
      )

  @Test
  fun aVerifiedAssociation_isAVerifiedAssociation() {
    val association =
        student.copy(accountType = AccountType.ASSOCIATION, isAssociationVerified = true)

    assertTrue(association.isVerifiedAssociation)
  }

  @Test
  fun anUnverifiedAssociation_isNot() {
    assertFalse(student.copy(accountType = AccountType.ASSOCIATION).isVerifiedAssociation)
  }

  @Test
  fun aStudent_isNotEvenWithTheFlagSet() {
    assertFalse(student.copy(isAssociationVerified = true).isVerifiedAssociation)
  }
}
