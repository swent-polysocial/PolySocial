// Contributors: Claude (the Create Event organizer row and its association variant, built from the
// Figma "Create event · association" frame, #134; large font sizes and TalkBack after a test
// audit, #134).
package com.polysocial.ui.event.create

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.exact

/** The row's height, the avatar's and the badge's in Figma, whatever the organizer. */
private val ROW_HEIGHT = 24.dp

/** Figma's gap between the avatar, the text and the badge. */
private val ROW_GAP = 8.dp

/**
 * The top row of the Create Event and Edit event form (#134): "Organized by you", or a verified
 * association's initials, "Organized by <name>" and the Verified badge (Figma "Create event ·
 * association"). While [organizer] is being read, the row holds the space "Organized by you" takes,
 * without drawing it, so no wrong organizer flashes. At the default font size nothing moves when
 * the organizer appears; at a larger one, an association's row can be a few dp taller, since its
 * badge grows with its text. A long name is shortened with an ellipsis so the badge stays whole.
 */
@Composable
internal fun OrganizerRow(organizer: Organizer) {
  when (organizer) {
    // While loading, the row holds "Organized by you" invisibly and silently, for its height.
    Organizer.Loading ->
        OrganizedByYou(
            Modifier.alpha(0f).clearAndSetSemantics {
              testTag = C.Tag.create_event_organizer_loading
            }
        )
    Organizer.You -> OrganizedByYou(Modifier.testTag(C.Tag.create_event_organized_by))
    is Organizer.Association ->
        Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)) {
          Avatar(organizerInitialsOf(organizer.name))
          Spacer(Modifier.width(ROW_GAP))
          Text(
              stringResource(R.string.create_event_organized_by, organizer.name),
              style = MaterialTheme.typography.labelMedium.exact(),
              color = Ink2,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              // Figma's text box starts 3 dp below the row's top, like "Organized by you".
              modifier =
                  Modifier.weight(1f, fill = false)
                      .padding(top = 3.dp)
                      .testTag(C.Tag.create_event_organized_by),
          )
          Spacer(Modifier.width(ROW_GAP))
          VerifiedBadge()
        }
  }
}

/** "Organized by you", as the form showed it before #134. */
@Composable
private fun OrganizedByYou(modifier: Modifier) {
  Text(
      stringResource(R.string.create_event_organized_by_you),
      style = MaterialTheme.typography.labelMedium,
      color = Ink2,
      modifier = modifier.heightIn(min = ROW_HEIGHT).padding(top = 3.dp),
  )
}

/**
 * The association's logo until logo uploads exist: its initials on a blue circle (Figma "AG"). They
 * only repeat the name next to them, so TalkBack skips them, and they keep Figma's size at any font
 * size so they always fit the 24 dp circle.
 */
@Composable
private fun Avatar(initials: String) {
  Box(
      Modifier.semantics(mergeDescendants = true) { hideFromAccessibility() }
          .size(ROW_HEIGHT)
          .background(InfoSoft, CircleShape)
          .testTag(C.Tag.create_event_organizer_avatar),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        initials,
        style =
            MaterialTheme.typography.labelSmall
                .copy(
                    fontSize = with(LocalDensity.current) { 10.dp.toSp() },
                    fontWeight = FontWeight.Bold,
                    lineHeight = TextUnit.Unspecified,
                )
                .exact(),
        color = Info,
    )
  }
}

/**
 * Figma's Verified badge: a check and "Verified" on a blue pill, 10 dp from each side. It is 24 dp
 * tall as in Figma and grows with a larger font size, so "Verified" is never cut off.
 */
@Composable
private fun VerifiedBadge() {
  Row(
      Modifier.semantics(mergeDescendants = true) {}
          .heightIn(min = ROW_HEIGHT)
          .background(InfoSoft, RoundedCornerShape(ROW_HEIGHT / 2))
          .padding(horizontal = 10.dp)
          .testTag(C.Tag.create_event_verified_badge),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
        painterResource(R.drawable.ic_verified),
        contentDescription = null,
        modifier = Modifier.size(12.dp),
    )
    // Figma puts the text's box 4 dp from the top, not centered: its 15.1 sp line would sit
    // 0.45 dp lower. The 4 dp below it keeps a larger text inside the pill.
    Text(
        stringResource(R.string.create_event_verified),
        style = VERIFIED_STYLE,
        color = Info,
        modifier = Modifier.align(Alignment.Top).padding(vertical = 4.dp),
    )
  }
}

/** Figma's badge text: 12 sp bold on a 15.1 sp line, kept whole (see CONTEXT.md, Traps). */
private val VERIFIED_STYLE
  @Composable
  get() =
      MaterialTheme.typography.labelSmall
          .copy(
              fontWeight = FontWeight.Bold,
              lineHeight = 15.1.sp,
              lineHeightStyle =
                  LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
          )
          .exact()

/**
 * The initials of an association's [name], as in Figma ("AGEPoly" → "AG"): the first two letters of
 * a one-word name, otherwise the first letters of its first and last words ("Fréquence Banane" →
 * "FB"), in capitals. Letters are whole characters (code points), so an emoji is never cut in half,
 * and each is capitalized on its own, so a letter without a one-letter capital ("ß") stays as is. A
 * character built from several code points (a flag, a family emoji) can still be cut: unlikely in
 * an association's name.
 */
internal fun organizerInitialsOf(name: String): String {
  // Any Unicode space separates words, a non-breaking one included.
  val words = name.split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }
  val letters =
      when (words.size) {
        0 -> emptyList()
        1 -> words.single().codePoints().limit(2).toArray().toList()
        else -> listOf(words.first().codePointAt(0), words.last().codePointAt(0))
      }
  return letters.joinToString("") { String(Character.toChars(Character.toUpperCase(it))) }
}
