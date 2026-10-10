// Contributors: Claude (shared loading state from the Figma "App shell" section, #43; spinner and
// text matched to the Figma frames, #74).
package com.polysocial.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.polysocial.resources.C
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.exact

/**
 * Full-screen loading state shared by every screen that waits for its content: a spinner and a
 * short [message] such as "Loading events…", centred as in the Figma "Tab · loading" and "App start
 * · loading" frames. It fills the screen, so a screen is never blank while it loads.
 */
@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.fillMaxSize().testTag(C.Tag.loading_state),
      verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spinner()
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).exact(),
        color = Ink2,
        textAlign = TextAlign.Center,
    )
  }
}

/**
 * Figma's spinner: a quarter arc in [Ink] on a ring of [Ink] at 20 %, both 3.2 dp wide, in a 32 dp
 * box, turning once a second.
 */
@Composable
private fun Spinner() {
  val rotation by
      rememberInfiniteTransition(label = "Loading spinner")
          .animateFloat(
              initialValue = 0f,
              targetValue = 360f,
              animationSpec =
                  infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
              label = "Spinner rotation",
          )
  Canvas(Modifier.size(32.dp).progressSemantics().rotate(rotation)) {
    val stroke = 3.2.dp.toPx()
    // The ring's centre line has a 12.8 dp radius: it starts 3.2 dp inside the 32 dp box.
    val inset = 3.2.dp.toPx()
    val topLeft = Offset(inset, inset)
    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    drawArc(
        color = Ink.copy(alpha = 0.2f),
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke),
    )
    drawArc(
        color = Ink,
        startAngle = -90f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
  }
}
