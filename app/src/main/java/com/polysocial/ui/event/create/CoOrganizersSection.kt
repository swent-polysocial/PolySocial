// Contributors: Claude (Opus 5.5, Anthropic) (Edit event's organizers and the "Add a co-organizer"
// sheet, from #400's Figma section 17, #370).
package com.polysocial.ui.event.create

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.event.MAX_ORGANIZERS
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.common.ConfirmationSheet
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.common.SheetSecondaryButton
import com.polysocial.ui.common.SheetTitle
import com.polysocial.ui.common.interestLabel
import com.polysocial.ui.common.secondaryTextStyle
import com.polysocial.ui.group.MemberAvatar
import com.polysocial.ui.group.initials
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3

/** Test tags of Edit event's organizers block and the "Add a co-organizer" sheet. */
object CoOrganizerTags {
  const val SECTION = "co_organizers"
  const val LABEL = "co_organizers_label"
  const val ADD = "co_organizers_add"
  const val HINT = "co_organizers_hint"
  const val FULL = "co_organizers_full"
  const val SHEET_LOADING = "add_co_organizer_loading"
  const val SHEET_BODY = "add_co_organizer_body"
  const val SHEET_NOBODY = "add_co_organizer_nobody"
  const val SHEET_FAILED = "add_co_organizer_failed"
  const val SHEET_RETRY = "add_co_organizer_retry"
  const val SHEET_CLOSE = "add_co_organizer_close"

  /** The row of the organizer [uid]. */
  fun organizer(uid: String) = "co_organizers_organizer_$uid"

  /** The × that removes the organizer [uid]. */
  fun remove(uid: String) = "co_organizers_remove_$uid"

  /** The sheet's row of the student [uid]. */
  fun student(uid: String) = "add_co_organizer_student_$uid"

  /** The Add button of the student [uid]. */
  fun addStudent(uid: String) = "add_co_organizer_add_$uid"
}

/** What the organizers block and its sheet report to [CreateEventViewModel]. */
data class CoOrganizerActions(
    val onRemove: (uid: String) -> Unit = {},
    val onOpenAdd: () -> Unit = {},
    val onAdd: (uid: String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCloseSheet: () -> Unit = {},
)

/**
 * Edit event's "Organizers · n of 5" block (#370, Figma section 17): each organizer with their
 * public profile, the creator first and without ×, then "Add a co-organizer", disabled at
 * [MAX_ORGANIZERS]. Nothing here is saved until Save changes. While [enabled] is false (saving),
 * nothing can be changed.
 */
@Composable
internal fun CoOrganizersSection(
    state: CoOrganizersUiState,
    enabled: Boolean,
    actions: CoOrganizerActions,
) {
  Column(
      verticalArrangement = Arrangement.spacedBy(14.dp),
      modifier = Modifier.fillMaxWidth().testTag(CoOrganizerTags.SECTION),
  ) {
    Text(
        stringResource(R.string.co_organizers_label, state.organizers.size, MAX_ORGANIZERS),
        style = MaterialTheme.typography.labelMedium,
        color = Ink2,
        modifier = Modifier.testTag(CoOrganizerTags.LABEL),
    )
    state.organizers.forEachIndexed { index, organizer ->
      OrganizerEntryRow(organizer, index, state.profilesLoading, enabled, actions.onRemove)
    }
    AddCoOrganizerButton(enabled = enabled && state.canAdd, onClick = actions.onOpenAdd)
    Text(
        if (state.canAdd) stringResource(R.string.co_organizers_hint)
        else pluralStringResource(R.plurals.co_organizers_full, MAX_ORGANIZERS, MAX_ORGANIZERS),
        style = secondaryTextStyle(),
        color = Ink3,
        modifier =
            Modifier.testTag(if (state.canAdd) CoOrganizerTags.HINT else CoOrganizerTags.FULL),
    )
  }
  state.sheet?.let { AddCoOrganizerSheetContent(it, actions) }
}

@Composable
private fun OrganizerEntryRow(
    organizer: OrganizerEntry,
    index: Int,
    profilesLoading: Boolean,
    enabled: Boolean,
    onRemove: (String) -> Unit,
) {
  val profile = organizer.profile
  val name =
      when {
        organizer.isYou -> stringResource(R.string.co_organizers_you)
        profile != null -> profile.displayName
        profilesLoading -> null
        else -> stringResource(R.string.co_organizers_unknown)
      }
  val details =
      when {
        organizer.isCreator -> stringResource(R.string.co_organizers_creator)
        organizer.isYou -> stringResource(R.string.co_organizers_co_organizer)
        profile != null ->
            listOf(
                    profile.section,
                    profile.year,
                    stringResource(R.string.co_organizers_co_organizer),
                )
                .filter(String::isNotBlank)
                .joinToString(" · ")
        profilesLoading -> null
        else -> stringResource(R.string.co_organizers_profile_unavailable)
      }
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxWidth().testTag(CoOrganizerTags.organizer(organizer.uid)),
  ) {
    // Decorative: the name next to it says who it is.
    Box(Modifier.clearAndSetSemantics {}) {
      when {
        // "You" sits on the first avatar color, as in Figma.
        organizer.isYou -> MemberAvatar(name.orEmpty(), 0, size = AVATAR_SIZE)
        profile != null -> MemberAvatar(initials(profile.displayName), index, size = AVATAR_SIZE)
        else -> Box(Modifier.size(AVATAR_SIZE).background(Disabled, CircleShape))
      }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      if (name == null || details == null) {
        // Loading: Figma's grey bars, as the group's members (#234).
        Box(Modifier.width(110.dp).height(10.dp).background(Disabled, CircleShape))
        Box(Modifier.width(170.dp).height(10.dp).background(Disabled, CircleShape))
      } else {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(details, style = secondaryTextStyle(), color = Ink2)
      }
    }
    if (organizer.canRemove) {
      val description =
          if (organizer.isYou) stringResource(R.string.co_organizers_remove_yourself)
          else stringResource(R.string.co_organizers_remove, name.orEmpty())
      Box(
          Modifier.size(44.dp)
              .clip(CircleShape)
              .clickable(enabled = enabled, role = Role.Button) { onRemove(organizer.uid) }
              .testTag(CoOrganizerTags.remove(organizer.uid)),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(R.drawable.ic_close),
            contentDescription = description,
            tint = if (enabled) Ink2 else Ink3,
            modifier = Modifier.size(22.dp),
        )
      }
    }
  }
}

/** Figma's outlined "+ Add a co-organizer" button, greyed when disabled. */
@Composable
private fun AddCoOrganizerButton(enabled: Boolean, onClick: () -> Unit) {
  val content = if (enabled) Ink else Ink3
  Row(
      Modifier.fillMaxWidth()
          .height(48.dp)
          .clip(RoundedCornerShape(24.dp))
          .background(Bg)
          .border(1.dp, Border, RoundedCornerShape(24.dp))
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(CoOrganizerTags.ADD),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp), tint = content)
    Text(
        stringResource(R.string.co_organizers_add),
        style = MaterialTheme.typography.titleSmall,
        color = content,
        modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/**
 * The "Add a co-organizer" sheet: the students going who aren't organizers yet, each with Add,
 * which puts them in the block and closes the sheet; or why nobody is listed. Close, Back, a swipe
 * or the scrim close it without adding anyone.
 */
@Composable
private fun AddCoOrganizerSheetContent(sheet: AddCoOrganizerSheet, actions: CoOrganizerActions) {
  ConfirmationSheet(locked = false, onDismissRequest = actions.onCloseSheet) { dismiss ->
    SheetTitle(R.string.add_co_organizer_title)
    when (sheet) {
      AddCoOrganizerSheet.Loading -> {
        SheetBody(R.string.add_co_organizer_body, CoOrganizerTags.SHEET_BODY)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 40.dp).testTag(CoOrganizerTags.SHEET_LOADING),
        ) {
          CircularProgressIndicator(Modifier.size(14.dp), color = Ink2, strokeWidth = 2.dp)
          Text(
              stringResource(R.string.add_co_organizer_loading),
              style = MaterialTheme.typography.labelLarge,
              color = Ink2,
          )
        }
      }
      is AddCoOrganizerSheet.Students -> {
        SheetBody(R.string.add_co_organizer_body, CoOrganizerTags.SHEET_BODY)
        sheet.students.forEachIndexed { index, student ->
          StudentRow(student, index) { actions.onAdd(student.uid) }
        }
      }
      AddCoOrganizerSheet.NobodyGoing ->
          SheetBody(R.string.add_co_organizer_nobody, CoOrganizerTags.SHEET_NOBODY)
      AddCoOrganizerSheet.Failed -> {
        SheetBody(R.string.add_co_organizer_failed, CoOrganizerTags.SHEET_FAILED)
        PrimaryButton(
            stringResource(R.string.add_co_organizer_retry),
            enabled = true,
            tag = CoOrganizerTags.SHEET_RETRY,
            onClick = actions.onRetry,
        )
      }
    }
    SheetSecondaryButton(
        R.string.add_co_organizer_close,
        true,
        CoOrganizerTags.SHEET_CLOSE,
        dismiss,
    )
  }
}

@Composable
private fun SheetBody(@StringRes text: Int, tag: String) {
  Text(
      stringResource(text),
      style = MaterialTheme.typography.bodyMedium,
      color = Ink2,
      modifier = Modifier.testTag(tag),
  )
}

/** A student going: name, then section · year · interests, as the group's members (#234). */
@Composable
private fun StudentRow(student: PublicProfile, index: Int, onAdd: () -> Unit) {
  val interests = student.interests.mapNotNull(::interestLabel).map { stringResource(it) }
  val details =
      listOf(student.section, student.year, interests.joinToString(", "))
          .filter(String::isNotBlank)
          .joinToString(" · ")
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxWidth().testTag(CoOrganizerTags.student(student.uid)),
  ) {
    Box(Modifier.clearAndSetSemantics {}) {
      MemberAvatar(initials(student.displayName), index + 1, size = AVATAR_SIZE)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          student.displayName,
          style = MaterialTheme.typography.titleSmall,
          color = Ink,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Text(details, style = secondaryTextStyle(), color = Ink2)
    }
    val description = stringResource(R.string.add_co_organizer_add_description, student.displayName)
    Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onAdd)
            .testTag(CoOrganizerTags.addStudent(student.uid)),
        contentAlignment = Alignment.Center,
    ) {
      Box(
          Modifier.width(64.dp).height(36.dp).background(Ink, RoundedCornerShape(18.dp)),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            stringResource(R.string.add_co_organizer_add),
            style = secondaryTextStyle(),
            fontWeight = FontWeight.Bold,
            color = Bg,
        )
      }
    }
  }
}

private val AVATAR_SIZE = 40.dp
