// Contributors: Claude (Opus 5.5, Anthropic) drafted this shared text layout helper, #492.
package com.polysocial.utils

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult

/**
 * The layout of the text this node draws, read through its `GetTextLayoutResult` semantics action:
 * its lines and their positions in pixels, for unit and device tests of alignment and wrapping.
 * Fails if the node draws no text.
 */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
  val layouts = mutableListOf<TextLayoutResult>()
  performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
  return layouts.single()
}
