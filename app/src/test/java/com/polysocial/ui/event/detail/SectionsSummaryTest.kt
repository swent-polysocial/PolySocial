// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the sections summary, #369).
package com.polysocial.ui.event.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionsSummaryTest {

  @Test
  fun aFewSections_areListed() {
    assertEquals("IN", sectionsSummary(listOf("IN")))
    assertEquals("IN, SC, MA", sectionsSummary(listOf("IN", "SC", "MA")))
  }

  @Test
  fun moreThanThree_countTheOthers() {
    assertEquals("IN, SC, MA +1", sectionsSummary(listOf("IN", "SC", "MA", "PH")))
    assertEquals("AR, CGC, CMS +15", sectionsSummary(com.polysocial.model.user.SECTIONS))
  }

  @Test
  fun noSections_isEmpty() {
    assertEquals("", sectionsSummary(emptyList()))
  }
}
