// Contributors: Claude (wrote these tests for the shared EPFL email checks, #208).
package com.polysocial.model.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpflEmailTest {
  @Test
  fun anEpflMailbox_isAnEpflEmail_andAnExactOne() {
    assertTrue(isEpflEmail("test.student@epfl.ch"))
    assertTrue(isExactEpflEmail("test.student@epfl.ch"))
  }

  @Test
  fun aCapitalisedDomain_isAnEpflEmail_butNotTheRulesExactOne() {
    // The rules match '@epfl[.]ch' case-sensitively, so only the lowercase domain gets access.
    assertTrue(isEpflEmail("Test.Student@EPFL.CH"))
    assertFalse(isExactEpflEmail("Test.Student@EPFL.CH"))
    assertTrue(isExactEpflEmail("Test.Student@epfl.ch"))
  }

  @Test
  fun otherDomainsAndLookalikes_areNeither() {
    for (email in
        listOf("club@example.org", "a@sub.epfl.ch", "a@epfl.ch.example.org", "@epfl.ch")) {
      assertFalse(email, isEpflEmail(email))
      assertFalse(email, isExactEpflEmail(email))
    }
  }
}
