// Contributors: Claude (the Verified pill shared by the event screens, from the Figma "Event
// detail" frame, #128).
package com.polysocial.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.PlusJakartaSans
import com.polysocial.ui.theme.exact

/**
 * Figma's blue pill for an association's event (#128): "Verified" next to its name, or "Verified
 * association" without it. It is 22 dp tall like in Figma and grows with a larger font size, so its
 * text is never cut off.
 */
@Composable
fun VerifiedPill(text: String, modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              // Read as one label, the text it holds.
              .semantics(mergeDescendants = true) {}
              .heightIn(min = 22.dp)
              .background(InfoSoft, RoundedCornerShape(50))
              .padding(horizontal = 8.dp, vertical = 3.dp),
      // Figma puts the text's box 3 dp from the top, not centered: its 15.1 sp line would sit
      // 0.45 dp lower.
      contentAlignment = Alignment.TopCenter,
  ) {
    Text(text = text, style = VERIFIED_PILL_STYLE, color = Info)
  }
}

private val VERIFIED_PILL_STYLE =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 15.1.sp,
        )
        .exact()
