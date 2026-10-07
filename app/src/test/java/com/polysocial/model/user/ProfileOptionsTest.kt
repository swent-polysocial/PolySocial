// Contributors: Claude Opus 5.5 (testing agent: the fixed section list, #34).
package com.polysocial.model.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileOptionsTest {
  @Test
  fun sections_areDistinctEpflCodesInAlphabeticalOrderWithoutShs() {
    assertEquals(SECTIONS.sorted(), SECTIONS)
    assertEquals(SECTIONS.distinct(), SECTIONS)
    for (code in SECTIONS) assertTrue(code, code.matches(Regex("[A-Z]{2,3}")))
    assertFalse(SECTIONS.contains("SHS"))
  }
}
