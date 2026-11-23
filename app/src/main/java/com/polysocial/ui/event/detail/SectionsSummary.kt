// Contributors: Claude (Sonnet 5.5, Anthropic) (the sections an event is open to, on the detail,
// #369).
package com.polysocial.ui.event.detail

/** How many section codes the detail's pill names before it counts the rest. */
internal const val SECTIONS_SHOWN = 3

/**
 * The sections an event is open to as the pill says them: the first [SECTIONS_SHOWN] codes, then
 * the number of the others ("IN, SC, MA +2"), so a long list never overflows the screen.
 */
internal fun sectionsSummary(sections: List<String>): String {
  val shown = sections.take(SECTIONS_SHOWN).joinToString(", ")
  val others = sections.size - SECTIONS_SHOWN
  return if (others > 0) "$shown +$others" else shown
}
