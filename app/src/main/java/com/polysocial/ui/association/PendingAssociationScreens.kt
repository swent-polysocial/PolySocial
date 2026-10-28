// Contributors: Claude (the pending association's Events, Map and Organization tabs, from the
// Figma "Association · pending" frames, #127).
package com.polysocial.ui.association

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationType
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.event.create.organizerInitialsOf
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.PlusJakartaSans
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft
import com.polysocial.ui.theme.exact

/** Test tags of the association's app (#127). */
object AssociationTags {
  const val SHELL = "association_shell"
  const val BOTTOM_BAR = "association_bottom_bar"
  const val NAV_EVENTS = "association_nav_events"
  const val NAV_MAP = "association_nav_map"
  const val NAV_ORGANIZATION = "association_nav_organization"
  const val SNACKBAR = "association_snackbar"

  const val HEADER_NAME = "association_header_name"
  const val HEADER_INITIALS = "association_header_initials"
  const val ORGANIZATION_INITIALS = "association_organization_initials"
  const val LOADING = "association_loading"

  const val EVENTS_SCREEN = "association_pending_events"
  const val UNDER_REVIEW = "association_pending_under_review"
  const val DRAFTS = "association_pending_drafts"
  const val NEW_DRAFT = "association_pending_new_draft"
  const val INVITE_MEMBERS = "association_pending_invite_members"

  const val MAP_SCREEN = "association_pending_map"

  const val ORGANIZATION_SCREEN = "association_pending_organization"
  const val NAME = "association_name"
  const val TYPE = "association_type"
  const val ABOUT = "association_about"
  const val BADGE = "association_under_review_badge"
  const val LOG_OUT = "association_log_out"
  const val LOAD_ERROR = "association_load_error"
}

// --- Figma's text styles on these screens (Plus Jakarta Sans), kept whole (CONTEXT.md, Traps) ---

private fun jakarta(weight: FontWeight, size: Int, lineHeight: Float) =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
        )
        .exact()

private val HEADER_NAME_STYLE = jakarta(FontWeight.SemiBold, 12, 15.1f)
private val HEADER_TITLE_STYLE = jakarta(FontWeight.ExtraBold, 22, 26f)
private val SMALL_INITIALS_STYLE = jakarta(FontWeight.ExtraBold, 13, 16.4f)
private val UNDER_REVIEW_STYLE = jakarta(FontWeight.Bold, 13, 16.4f)
private val CARD_TITLE_STYLE = jakarta(FontWeight.Bold, 17, 24f)
private val STEP_STYLE = jakarta(FontWeight.Normal, 14, 17.6f)
private val ROW_STYLE = jakarta(FontWeight.SemiBold, 15, 18.9f)
private val BUTTON_STYLE = jakarta(FontWeight.Bold, 14, 17.6f)
private val ORGANIZATION_TITLE_STYLE = jakarta(FontWeight.ExtraBold, 26, 32.8f)
private val LARGE_INITIALS_STYLE = jakarta(FontWeight.ExtraBold, 22, 27.7f)
private val NAME_STYLE = jakarta(FontWeight.ExtraBold, 19, 23.9f)
private val TYPE_STYLE = jakarta(FontWeight.Normal, 13, 16.4f)
private val BADGE_STYLE = jakarta(FontWeight.Bold, 12, 15.1f)
private val ABOUT_STYLE = jakarta(FontWeight.Normal, 14, 21f)

/** Figma's 20 dp margin on each side of every association screen. */
private val MARGIN = 20.dp

/**
 * The Events and Map tabs' header: the association's initials, its name and the tab's [title]. Its
 * name and initials show once it is read; the tab's title always does.
 */
@Composable
private fun PendingHeader(association: Association?, @StringRes title: Int) {
  Row(
      Modifier.fillMaxWidth().padding(start = MARGIN, end = MARGIN, top = 19.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    InitialsTile(
        association,
        size = 36,
        radius = 10,
        SMALL_INITIALS_STYLE,
        AssociationTags.HEADER_INITIALS,
    )
    Spacer(Modifier.width(10.dp))
    Column {
      if (association != null) {
        Text(
            association.name,
            style = HEADER_NAME_STYLE,
            color = Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(AssociationTags.HEADER_NAME),
        )
      }
      Text(
          stringResource(title),
          style = HEADER_TITLE_STYLE,
          color = Ink,
          modifier = Modifier.semantics { heading() },
      )
    }
  }
}

/**
 * The association's logo until logo uploads exist: its initials on Figma's amber tile. They only
 * repeat the name shown next to them, so TalkBack skips them.
 */
@Composable
private fun InitialsTile(
    association: Association?,
    size: Int,
    radius: Int,
    style: TextStyle,
    tag: String,
) {
  Box(
      Modifier.size(size.dp)
          .background(WarningSoft, RoundedCornerShape(radius.dp))
          .semantics(mergeDescendants = true) { hideFromAccessibility() }
          .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    if (association != null) {
      Text(organizerInitialsOf(association.name), style = style, color = Warning, maxLines = 1)
    }
  }
}

/**
 * The Events tab while an admin reviews the association (Figma "Association · pending · Events"):
 * the review steps, then Drafts, New draft and Invite members, which aren't built yet, so each
 * calls [onNotAvailable].
 */
@Composable
fun PendingEventsScreen(association: Association?, onNotAvailable: () -> Unit) {
  Column(
      Modifier.fillMaxSize()
          .background(Bg)
          .verticalScroll(rememberScrollState())
          .testTag(AssociationTags.EVENTS_SCREEN)
  ) {
    PendingHeader(association, R.string.association_tab_events)
    Spacer(Modifier.height(16.dp))
    ReviewCard()
    Spacer(Modifier.height(16.dp))
    DraftsRow(onNotAvailable)
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MARGIN),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PillButton(
          R.string.association_pending_new_draft,
          filled = true,
          tag = AssociationTags.NEW_DRAFT,
          onClick = onNotAvailable,
          modifier = Modifier.weight(160f),
      )
      PillButton(
          R.string.association_pending_invite_members,
          filled = false,
          tag = AssociationTags.INVITE_MEMBERS,
          onClick = onNotAvailable,
          modifier = Modifier.weight(150f),
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}

/**
 * Figma's amber "Under review" card: the request is sent, the team checks it, then it goes live.
 */
@Composable
private fun ReviewCard() {
  Column(
      Modifier.fillMaxWidth()
          .padding(horizontal = MARGIN)
          .background(WarningSoft, RoundedCornerShape(20.dp))
          .padding(18.dp)
          .testTag(AssociationTags.UNDER_REVIEW)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Image(painterResource(R.drawable.ic_clock), contentDescription = null)
      Spacer(Modifier.width(8.dp))
      Text(
          stringResource(R.string.association_pending_under_review),
          style = UNDER_REVIEW_STYLE,
          color = Warning,
      )
    }
    Spacer(Modifier.height(14.dp))
    Text(
        stringResource(R.string.association_pending_title),
        style = CARD_TITLE_STYLE,
        color = Ink,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(14.dp))
    ReviewStep(
        R.drawable.ic_review_step_done,
        R.string.association_pending_step_sent,
        R.string.association_pending_step_done,
        Ink,
        FontWeight.Normal,
    )
    Spacer(Modifier.height(10.dp))
    ReviewStep(
        R.drawable.ic_review_step_current,
        R.string.association_pending_step_checking,
        R.string.association_pending_step_current,
        Ink,
        FontWeight.Bold,
    )
    Spacer(Modifier.height(10.dp))
    ReviewStep(
        R.drawable.ic_review_step_next,
        R.string.association_pending_step_verified,
        R.string.association_pending_step_upcoming,
        Ink2,
        FontWeight.Normal,
    )
  }
}

/** One review step: its marker, then its text, read by TalkBack with the step's state. */
@Composable
private fun ReviewStep(
    @DrawableRes marker: Int,
    @StringRes text: Int,
    @StringRes state: Int,
    color: androidx.compose.ui.graphics.Color,
    weight: FontWeight,
) {
  val label = stringResource(text)
  val description = "$label, ${stringResource(state)}"
  Row(
      Modifier.clearAndSetSemantics { contentDescription = description },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(painterResource(marker), contentDescription = null)
    Spacer(Modifier.width(10.dp))
    Text(label, style = STEP_STYLE.copy(fontWeight = weight), color = color)
  }
}

/** Figma's "Drafts" list row, with a divider under it. */
@Composable
private fun DraftsRow(onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth()
          .padding(horizontal = MARGIN)
          .heightIn(min = 53.dp)
          .drawBehind {
            val y = size.height - 0.5.dp.toPx()
            drawLine(Border, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
          }
          .clickable(role = Role.Button, onClick = onClick)
          .testTag(AssociationTags.DRAFTS),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        painterResource(R.drawable.ic_tab_events),
        contentDescription = null,
        tint = Ink,
        modifier = Modifier.size(22.dp),
    )
    Spacer(Modifier.width(14.dp))
    Text(
        stringResource(R.string.association_pending_drafts),
        style = ROW_STYLE,
        color = Ink,
        modifier = Modifier.weight(1f),
    )
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = Ink3,
        modifier = Modifier.size(18.dp),
    )
  }
}

/** Figma's 48 dp pill: dark and [filled], or white with a border. */
@Composable
private fun PillButton(
    @StringRes text: Int,
    filled: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(24.dp)
  Box(
      modifier
          .heightIn(min = 48.dp)
          .background(if (filled) Ink else Bg, shape)
          .then(if (filled) Modifier else Modifier.border(1.dp, Border, shape))
          .clickable(role = Role.Button, onClick = onClick)
          .padding(horizontal = 12.dp, vertical = 8.dp)
          .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        stringResource(text),
        style = BUTTON_STYLE,
        color = if (filled) Bg else Ink,
        textAlign = TextAlign.Center,
    )
  }
}

/** The Map tab before an admin verifies the association (Figma "Association · pending · Map"). */
@Composable
fun PendingMapScreen(association: Association?) {
  Column(
      Modifier.fillMaxSize()
          .background(Bg)
          .verticalScroll(rememberScrollState())
          .testTag(AssociationTags.MAP_SCREEN),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    PendingHeader(association, R.string.association_tab_map)
    // Figma places the tile 187 dp under the header.
    Spacer(Modifier.height(187.dp))
    Box(
        Modifier.size(72.dp).background(WarningSoft, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_tab_map),
          contentDescription = null,
          tint = Warning,
          modifier = Modifier.size(32.dp),
      )
    }
    Spacer(Modifier.height(24.dp))
    Text(
        stringResource(R.string.association_pending_map_title),
        style = CARD_TITLE_STYLE,
        color = Ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp).semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.association_pending_map_body),
        style = STEP_STYLE,
        color = Ink2,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp),
    )
    Spacer(Modifier.height(24.dp))
  }
}

/**
 * The Organization tab while an admin reviews the association (Figma "Association · pending ·
 * Organization"): its details with the "Under review" badge, and Log out, a spinner while they are
 * read, which is always there, even when the details couldn't be read ([onRetry]).
 */
@Composable
fun PendingOrganizationScreen(
    state: PendingAssociationUiState,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
) {
  // The error state scrolls itself and takes the rest of the screen; the details scroll here.
  val scroll =
      if (state == PendingAssociationUiState.Failed) Modifier
      else Modifier.verticalScroll(rememberScrollState())
  Column(
      Modifier.fillMaxSize()
          .background(Bg)
          .then(scroll)
          .padding(horizontal = MARGIN)
          .testTag(AssociationTags.ORGANIZATION_SCREEN)
  ) {
    Row(
        Modifier.fillMaxWidth().padding(top = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          stringResource(R.string.association_tab_organization),
          style = ORGANIZATION_TITLE_STYLE,
          color = Ink,
          modifier = Modifier.weight(1f).semantics { heading() },
      )
      // Keeps the height of Figma's pencil row, which Edit details adds (#127).
      Spacer(Modifier.height(44.dp))
    }
    when (state) {
      is PendingAssociationUiState.Loaded -> {
        Spacer(Modifier.height(14.dp))
        Details(state.association)
        Spacer(Modifier.height(13.dp))
        LogOutRow(onLogOut)
      }
      PendingAssociationUiState.Loading -> {
        Spacer(Modifier.height(14.dp))
        LogOutRow(onLogOut)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(
            color = Ink,
            strokeWidth = 2.dp,
            modifier =
                Modifier.size(24.dp)
                    .align(Alignment.CenterHorizontally)
                    .testTag(AssociationTags.LOADING),
        )
      }
      PendingAssociationUiState.Failed -> {
        Spacer(Modifier.height(14.dp))
        LogOutRow(onLogOut)
        ErrorState(
            title = stringResource(R.string.association_load_error_title),
            message = stringResource(R.string.association_load_error_message),
            onRetry = onRetry,
            modifier = Modifier.weight(1f).testTag(AssociationTags.LOAD_ERROR),
        )
      }
    }
  }
}

/** The association's initials, name, type, "Under review" badge and about. */
@Composable
private fun Details(association: Association) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    InitialsTile(
        association,
        size = 64,
        radius = 18,
        LARGE_INITIALS_STYLE,
        AssociationTags.ORGANIZATION_INITIALS,
    )
    Spacer(Modifier.width(14.dp))
    Column {
      Text(
          association.name,
          style = NAME_STYLE,
          color = Ink,
          modifier = Modifier.testTag(AssociationTags.NAME),
      )
      Spacer(Modifier.height(3.dp))
      Text(
          stringResource(R.string.association_type_at_epfl, stringResource(association.type.label)),
          style = TYPE_STYLE,
          color = Ink2,
          modifier = Modifier.testTag(AssociationTags.TYPE),
      )
      Spacer(Modifier.height(4.dp))
      UnderReviewBadge()
    }
  }
  Spacer(Modifier.height(15.dp))
  Text(
      association.about,
      style = ABOUT_STYLE,
      color = Ink2,
      modifier = Modifier.testTag(AssociationTags.ABOUT),
  )
}

/** Figma's amber badge, in place of "Verified association" until an admin verifies it. */
@Composable
private fun UnderReviewBadge() {
  Row(
      Modifier.heightIn(min = 24.dp)
          .background(WarningSoft, RoundedCornerShape(12.dp))
          .padding(start = 10.dp, end = 10.dp)
          .semantics(mergeDescendants = true) {}
          .testTag(AssociationTags.BADGE),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
        painterResource(R.drawable.ic_clock),
        contentDescription = null,
        modifier = Modifier.size(12.dp),
    )
    Spacer(Modifier.width(4.dp))
    Text(
        stringResource(R.string.association_pending_under_review),
        style = BADGE_STYLE,
        color = Warning,
    )
  }
}

/** Figma's Log out row: the icon, then "Log out", in a 50 dp row. */
@Composable
private fun LogOutRow(onLogOut: () -> Unit) {
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 50.dp)
          .clickable(role = Role.Button, onClick = onLogOut)
          .testTag(AssociationTags.LOG_OUT),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        painterResource(R.drawable.ic_log_out),
        contentDescription = null,
        tint = Ink,
        modifier = Modifier.size(22.dp),
    )
    Spacer(Modifier.width(14.dp))
    Text(stringResource(R.string.association_log_out), style = ROW_STYLE, color = Ink)
  }
}

/** The name of an [AssociationType], as on the Figma setup chips. */
@get:StringRes
internal val AssociationType.label: Int
  get() =
      when (this) {
        AssociationType.ASSOCIATION -> R.string.association_type_association
        AssociationType.COMMISSION -> R.string.association_type_commission
        AssociationType.CLUB -> R.string.association_type_club
        AssociationType.EPFL_UNIT -> R.string.association_type_epfl_unit
      }
