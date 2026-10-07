// Contributors: Claude (profile step screen for #34, built from the Figma "First proposal
// revamped" Profile frames).
package com.polysocial.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.polysocial.R
import com.polysocial.model.user.SECTIONS
import com.polysocial.model.user.YEARS
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Surface

/** Test tags of the profile step. */
object ProfileSetupTestTags {
  const val SCREEN = "profile_setup_screen"
  const val BACK = "profile_setup_back"
  const val FULL_NAME = "profile_setup_full_name"
  const val EMAIL = "profile_setup_email"
  const val SECTION = "profile_setup_section"
  const val YEAR = "profile_setup_year"
  const val ERROR = "profile_setup_error"
  const val CONTINUE = "profile_setup_continue"

  fun sectionOption(code: String) = "profile_setup_section_$code"

  fun yearOption(code: String) = "profile_setup_year_$code"
}

/**
 * The profile step after email verification. Reports where to go next through the callbacks, so the
 * app's navigation (#74) decides how to get there.
 */
@Composable
fun ProfileSetupScreen(
    onBack: () -> Unit,
    onProfileSaved: () -> Unit,
    viewModel: ProfileSetupViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  LaunchedEffect(state.status) { if (state.status == ProfileSetupStatus.Saved) onProfileSaved() }
  ProfileSetupContent(
      state = state,
      onBack = onBack,
      onDisplayNameChange = viewModel::onDisplayNameChange,
      onSectionChange = viewModel::onSectionChange,
      onYearChange = viewModel::onYearChange,
      onContinue = viewModel::onContinue,
  )
}

/** Draws the profile step for [state]; all measurements come from the Figma Profile frames. */
@Composable
fun ProfileSetupContent(
    state: ProfileSetupUiState,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSectionChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
  val typography = MaterialTheme.typography
  val saving = state.status == ProfileSetupStatus.Saving
  val editable = !saving && state.status != ProfileSetupStatus.Saved

  Box(Modifier.fillMaxSize().background(Bg).testTag(ProfileSetupTestTags.SCREEN)) {
    // On a short screen, with large text or the keyboard open, the form scrolls so Continue stays
    // reachable. Otherwise the bottom group sits at the bottom, as in Figma.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Column(
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .heightIn(min = maxHeight)
              .padding(horizontal = 20.dp),
          verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.fillMaxWidth()) {
          TopBar(onBack)
          Spacer(Modifier.height(11.5.dp))
          Text(
              stringResource(R.string.profile_title),
              style = typography.headlineMedium,
              color = Ink,
          )
          Spacer(Modifier.height(7.5.dp))
          Text(
              stringResource(R.string.profile_subtitle),
              style = typography.bodyLarge,
              color = Ink2,
              // Figma's text box is narrower than the column, which sets where the line breaks.
              modifier = Modifier.width(293.5.dp),
          )
          Spacer(Modifier.height(17.dp))
          Initials(state.displayName)
          Spacer(Modifier.height(15.5.dp))

          FieldLabel(R.string.profile_full_name)
          Spacer(Modifier.height(5.5.dp))
          NameField(state.displayName, onDisplayNameChange, enabled = editable, faded = saving)
          Spacer(Modifier.height(13.5.dp))

          EmailLabel()
          Spacer(Modifier.height(5.5.dp))
          EmailField(state.email)
          Spacer(Modifier.height(13.5.dp))

          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Picker(
                label = R.string.profile_section,
                value = state.section,
                options = SECTIONS,
                onPick = onSectionChange,
                enabled = editable,
                faded = saving,
                fieldTag = ProfileSetupTestTags.SECTION,
                optionTag = ProfileSetupTestTags::sectionOption,
                modifier = Modifier.weight(1f),
            )
            Picker(
                label = R.string.profile_year,
                value = state.year,
                options = YEARS,
                onPick = onYearChange,
                enabled = editable,
                faded = saving,
                fieldTag = ProfileSetupTestTags.YEAR,
                optionTag = ProfileSetupTestTags::yearOption,
                modifier = Modifier.weight(1f),
            )
          }
        }

        Column(Modifier.fillMaxWidth()) {
          // Keeps a gap above the privacy note when the form scrolls; hidden in the free space
          // otherwise.
          Spacer(Modifier.height(24.dp))
          PrivacyNote()
          if (state.status == ProfileSetupStatus.CouldNotSave) {
            Spacer(Modifier.height(6.dp))
            ErrorMessage()
            Spacer(Modifier.height(50.dp))
          } else {
            Spacer(Modifier.height(11.dp))
          }
          ContinueButton(state = state, onClick = onContinue)
          Spacer(Modifier.height(28.dp))
        }
      }
    }
  }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
  // Back arrow: 22 dp icon at (19, 23), inside a 44 dp touch target; "Step 1 of 2" and the two
  // progress bars end at the right margin, centred on the arrow.
  Row(
      Modifier.fillMaxWidth().padding(top = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.offset(x = (-12).dp)
            .size(44.dp)
            .clickable(onClick = onBack)
            .testTag(ProfileSetupTestTags.BACK),
        contentAlignment = Alignment.Center,
    ) {
      Image(painterResource(R.drawable.ic_back), stringResource(R.string.profile_back))
    }
    Spacer(Modifier.weight(1f))
    Text(
        stringResource(R.string.profile_step),
        style = MaterialTheme.typography.bodySmall,
        color = Ink2,
    )
    Spacer(Modifier.width(8.5.dp))
    Box(Modifier.size(width = 20.dp, height = 4.dp).background(Ink, RoundedCornerShape(2.dp)))
    Spacer(Modifier.width(4.dp))
    Box(Modifier.size(width = 20.dp, height = 4.dp).background(Border, RoundedCornerShape(2.dp)))
  }
}

/** The avatar: the first letters of the first and last name, since there is no photo yet. */
@Composable
private fun Initials(displayName: String) {
  Box(
      Modifier.size(56.dp).background(SuccessSoft, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        initialsOf(displayName),
        style =
            MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = TextUnit.Unspecified,
            ),
        color = Success,
    )
  }
}

@Composable
private fun FieldLabel(@StringRes text: Int, modifier: Modifier = Modifier) {
  Text(
      stringResource(text),
      style = MaterialTheme.typography.labelMedium,
      color = Ink2,
      modifier = modifier,
  )
}

/** The field text style: Body with Figma's 20 px line height inside fields. */
@Composable
private fun fieldTextStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp)

@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    faded: Boolean,
) {
  // While saving, Figma fades the editable fields to 45%.
  val shape = MaterialTheme.shapes.small
  BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = true,
      textStyle = fieldTextStyle().copy(color = Ink),
      cursorBrush = SolidColor(Ink),
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
      modifier =
          Modifier.fillMaxWidth()
              .alpha(if (faded) 0.45f else 1f)
              .testTag(ProfileSetupTestTags.FULL_NAME),
      decorationBox = { innerTextField ->
        Box(
            Modifier.fillMaxWidth()
                .height(50.dp)
                .background(Bg, shape)
                .border(1.dp, Border, shape)
                .padding(horizontal = 15.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
          innerTextField()
        }
      },
  )
}

@Composable
private fun EmailLabel() {
  // "Verified" with its check mark ends 62.5 dp before the right margin, as in Figma.
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    FieldLabel(R.string.profile_email)
    Spacer(Modifier.weight(1f))
    Image(painterResource(R.drawable.ic_check), contentDescription = null)
    Spacer(Modifier.width(4.dp))
    Text(
        stringResource(R.string.profile_verified),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
        color = Success,
    )
    Spacer(Modifier.width(62.5.dp))
  }
}

/** The verified email: shown, never editable. */
@Composable
private fun EmailField(email: String) {
  val shape = MaterialTheme.shapes.small
  Row(
      Modifier.fillMaxWidth()
          .height(48.dp)
          .background(Surface, shape)
          .border(1.dp, Border, shape)
          .padding(start = 15.dp, end = 14.dp)
          .testTag(ProfileSetupTestTags.EMAIL),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(email, style = fieldTextStyle(), color = Ink2, modifier = Modifier.weight(1f))
    Image(painterResource(R.drawable.ic_lock), contentDescription = null)
  }
}

/** A field that opens a menu of fixed [options] (Figma "choosing section" and "choosing year"). */
@Composable
private fun Picker(
    @StringRes label: Int,
    value: String?,
    options: List<String>,
    onPick: (String) -> Unit,
    enabled: Boolean,
    faded: Boolean,
    fieldTag: String,
    optionTag: (String) -> String,
    modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val shape = MaterialTheme.shapes.small
  Column(modifier) {
    FieldLabel(label)
    Spacer(Modifier.height(5.5.dp))
    Box {
      Row(
          Modifier.fillMaxWidth()
              .height(48.dp)
              .alpha(if (faded) 0.45f else 1f)
              .background(Bg, shape)
              .border(if (expanded) 2.dp else 1.dp, if (expanded) Ink else Border, shape)
              .clickable(enabled = enabled) { expanded = true }
              .padding(start = 14.dp, end = 14.dp)
              .testTag(fieldTag),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            value ?: stringResource(R.string.profile_choose),
            style = fieldTextStyle(),
            color = if (value == null) Ink3 else Ink,
            modifier = Modifier.weight(1f),
        )
        Image(painterResource(R.drawable.ic_chevron_down), contentDescription = null)
      }
      DropdownMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
          offset = DpOffset(0.dp, 8.dp),
          shape = shape,
          containerColor = Bg,
          border = BorderStroke(1.dp, Border),
          shadowElevation = 8.dp,
          // Seven 40 dp items are visible at once; the rest scroll.
          modifier = Modifier.width(154.dp).heightIn(max = 296.dp),
      ) {
        options.forEach { option ->
          MenuItem(
              text = option,
              selected = option == value,
              onClick = {
                expanded = false
                onPick(option)
              },
              modifier = Modifier.testTag(optionTag(option)),
          )
        }
      }
    }
  }
}

@Composable
private fun MenuItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier
          .padding(horizontal = 4.dp)
          .fillMaxWidth()
          .height(40.dp)
          .background(if (selected) Surface else Bg, RoundedCornerShape(8.dp))
          .clickable(onClick = onClick)
          .padding(start = 12.dp, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text,
        style =
            fieldTextStyle()
                .copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
        color = Ink,
        modifier = Modifier.weight(1f),
    )
    if (selected) {
      Image(
          painterResource(R.drawable.ic_check),
          contentDescription = null,
          colorFilter = ColorFilter.tint(Ink),
      )
    }
  }
}

@Composable
private fun PrivacyNote() {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Image(painterResource(R.drawable.ic_shield), contentDescription = null)
    Text(
        stringResource(R.string.profile_privacy),
        style =
            MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Normal,
                lineHeight = 17.sp,
            ),
        color = Ink3,
        modifier = Modifier.padding(top = 1.dp),
    )
  }
}

@Composable
private fun ErrorMessage() {
  Row(
      Modifier.testTag(ProfileSetupTestTags.ERROR),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Image(
        painterResource(R.drawable.ic_error),
        contentDescription = null,
        modifier = Modifier.padding(top = 1.dp),
    )
    Text(
        stringResource(R.string.profile_could_not_save),
        style = MaterialTheme.typography.bodySmall,
        color = AccentText,
    )
  }
}

@Composable
private fun ContinueButton(state: ProfileSetupUiState, onClick: () -> Unit) {
  val saving = state.status == ProfileSetupStatus.Saving
  val enabled = state.canContinue
  val shape = MaterialTheme.shapes.large
  Row(
      Modifier.fillMaxWidth()
          .height(52.dp)
          .background(
              when {
                saving -> Bg
                enabled -> Ink
                else -> Disabled
              },
              shape,
          )
          .then(if (saving) Modifier.border(1.dp, Border, shape) else Modifier)
          .clickable(enabled = enabled, onClick = onClick)
          .testTag(ProfileSetupTestTags.CONTINUE),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (saving) {
      CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = Ink,
          trackColor = Ink.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
      )
    }
    Text(
        stringResource(if (saving) R.string.profile_saving else R.string.profile_continue),
        style = MaterialTheme.typography.titleSmall,
        color =
            when {
              saving -> Ink
              enabled -> Bg
              else -> Ink3
            },
    )
  }
}

/** The first letters of the first and last words of [name], in capitals: "Franek Najda" → "FN". */
internal fun initialsOf(name: String): String {
  val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
  if (words.isEmpty()) return ""
  val first = words.first().first()
  val last = if (words.size > 1) words.last().first().toString() else ""
  return "$first$last".uppercase()
}
