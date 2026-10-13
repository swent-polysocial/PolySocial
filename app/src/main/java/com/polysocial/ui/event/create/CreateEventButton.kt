// Contributors: Claude (the "+" button from the Figma "Create event" frames, sections 03 and 04,
// #110).
package com.polysocial.ui.event.create

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.Ink

private val shape = RoundedCornerShape(18.dp)

/** The "+" button that opens Create Event: a 56 dp dark tile with a white plus, as in Figma. */
@Composable
fun CreateEventButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              .size(56.dp)
              .dropShadow(
                  shape,
                  Shadow(
                      radius = 7.dp,
                      color = Ink.copy(alpha = 0.25f),
                      offset = DpOffset(0.dp, 4.dp),
                  ),
              )
              .clip(shape)
              .background(Ink)
              .clickable(role = Role.Button, onClick = onClick)
              .testTag(C.Tag.create_event_button),
      contentAlignment = Alignment.Center,
  ) {
    Image(painterResource(R.drawable.ic_plus), stringResource(R.string.create_event_open))
  }
}
