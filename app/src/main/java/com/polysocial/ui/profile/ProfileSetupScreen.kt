// Contributors: Claude (profile step screen for #34, built from the Figma "First proposal
// revamped" Profile frames; layout that grows with large text, one save report and picker
// accessibility after review; plain Column layout after review).
package com.polysocial.ui.profile

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import kotlin.math.roundToInt

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
 * app's navigation (#74) decides how to get there. [onProfileSaved] is called once per visit, when
 * the profile exists: the caller must then leave this step and remove it from the back stack, or
 * the user could come back to a finished, locked form.
 */
@Composable
fun ProfileSetupScreen(
    onBack: () -> Unit,
    onProfileSaved: () -> Unit,
    viewModel: ProfileSetupViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  // Reported once: after a rotation the ViewModel is still Saved, and the effect runs again.
  var savedReported by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(state.status) {
    if (state.status == ProfileSetupStatus.Saved && !savedReported) {
      savedReported = true
      onProfileSaved()
    }
  }
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
        // The gaps are the Figma frame's; a taller element (larger font size) pushes the ones below
        // it down.
        Column(Modifier.fillMaxWidth()) {
          TopBar(onBack)
          Spacer(Modifier.height(11.5.dp))
          Text(
              stringResource(R.string.profile_title),
              style = typography.headlineMedium.figmaLines(),
              color = Ink,
              modifier = Modifier.heightIn(min = 32.dp),
          )
          Spacer(Modifier.height(7.5.dp))
          Text(
              stringResource(R.string.profile_subtitle),
              style = typography.bodyLarge.figmaLines(),
              color = Ink2,
              // Figma's text box is narrower than the column, which sets where the line breaks.
              modifier = Modifier.widthIn(max = 293.5.dp).fillMaxWidth().heightIn(min = 44.dp),
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
                // Figma places the section value 14 dp and the year value 13 dp inside the field.
                textStart = 14.dp,
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
                textStart = 13.dp,
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
  // Back arrow: 22 dp icon at (19, 23), inside a 44 dp touch target. "Step 1 of 2" (y 26) and the
  // two progress bars (y 32) end at the right margin.
  Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
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
        style = MaterialTheme.typography.bodySmall.exact(),
        color = Ink2,
        modifier = Modifier.padding(top = 14.dp),
    )
    Spacer(Modifier.width(9.5.dp))
    ProgressBar(Ink)
    Spacer(Modifier.width(4.dp))
    ProgressBar(Border)
  }
}

@Composable
private fun ProgressBar(color: Color) {
  Box(
      Modifier.padding(top = 20.dp)
          .size(width = 20.dp, height = 4.dp)
          .background(color, RoundedCornerShape(2.dp))
  )
}

/**
 * Positions glyphs at fractional pixels, like Figma. By default Android rounds each glyph to a
 * whole pixel, which made long texts about 1 dp narrower than in Figma.
 */
private fun TextStyle.exact(): TextStyle = copy(textMotion = TextMotion.Animated)

/**
 * [exact], and lays lines out like Figma: each line takes its full line height, with the text
 * centred in it, and nothing is trimmed above the first line or below the last (Compose trims by
 * default).
 */
private fun TextStyle.figmaLines(): TextStyle =
    exact()
        .copy(
            lineHeightStyle =
                LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        )

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
            MaterialTheme.typography.bodyLarge
                .copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = TextUnit.Unspecified,
                )
                .exact(),
        color = Success,
    )
  }
}

@Composable
private fun FieldLabel(@StringRes text: Int, modifier: Modifier = Modifier) {
  // 17 dp: the height of Figma's label box, so the field below starts exactly where Figma's does.
  Text(
      stringResource(text),
      style = MaterialTheme.typography.labelMedium.exact(),
      color = Ink2,
      modifier = modifier.heightIn(min = 17.dp),
  )
}

/** The field text style: Body with Figma's 20 px line height inside fields. */
@Composable
private fun fieldTextStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp).exact()

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
                .heightIn(min = 50.dp)
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
  Row(
      Modifier.fillMaxWidth().heightIn(min = 17.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    FieldLabel(R.string.profile_email)
    Spacer(Modifier.weight(1f))
    Image(painterResource(R.drawable.ic_check), contentDescription = null)
    Spacer(Modifier.width(4.dp))
    Text(
        stringResource(R.string.profile_verified),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold).exact(),
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
          .heightIn(min = 48.dp)
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
    textStart: Dp,
    modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  var fieldBottom by remember { mutableIntStateOf(0) }
  val shape = MaterialTheme.shapes.small
  val labelText = stringResource(label)
  val shownValue = value ?: stringResource(R.string.profile_choose)
  Column(modifier) {
    // The field announces its label itself, so screen readers don't read it twice.
    FieldLabel(label, Modifier.semantics { hideFromAccessibility() })
    Spacer(Modifier.height(5.5.dp))
    Box(Modifier.onGloballyPositioned { fieldBottom = it.boundsInWindow().bottom.roundToInt() }) {
      Row(
          Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .alpha(if (faded) 0.45f else 1f)
              .background(Bg, shape)
              .border(if (expanded) 2.dp else 1.dp, if (expanded) Ink else Border, shape)
              .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
              // Screen readers announce "Section, IN, drop-down list".
              .semantics {
                contentDescription = labelText
                stateDescription = shownValue
              }
              .padding(start = textStart, end = 14.dp)
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
      if (expanded) {
        OptionsMenu(
            options = options,
            selected = value,
            onPick = {
              expanded = false
              onPick(it)
            },
            onDismiss = { expanded = false },
            optionTag = optionTag,
            fieldBottom = fieldBottom,
        )
      }
    }
  }
}

/** Room around the menu inside its popup window, so the Figma shadow (blur 24, y 8) isn't cut. */
private val ShadowRoom = 32.dp

/**
 * The open picker, drawn like the Figma "choosing" frames: 8 dp below the field, 154 x 298 dp, with
 * the chosen value scrolled to the fourth of the seven visible items. It always opens below the
 * field, as in Figma; when the screen is too short for 298 dp, it ends above the navigation bar and
 * scrolls. (Material's DropdownMenu would open above the field instead.)
 *
 * @param fieldBottom the bottom of the field, in window pixels.
 */
@Composable
private fun OptionsMenu(
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    optionTag: (String) -> String,
    fieldBottom: Int,
) {
  val density = LocalDensity.current
  val gap = with(density) { 8.dp.roundToPx() }
  val room = with(density) { ShadowRoom.roundToPx() }
  val itemHeight = with(density) { 40.dp.toPx() }
  val spaceBelow =
      LocalWindowInfo.current.containerSize.height -
          WindowInsets.navigationBars.getBottom(density) -
          fieldBottom -
          gap
  // At least one item (40 dp plus the 9 dp padding above and below) stays visible.
  val maxHeight = with(density) { spaceBelow.toDp().coerceIn(58.dp, 298.dp) }
  val scroll = rememberScrollState()
  LaunchedEffect(Unit) {
    val index = options.indexOf(selected)
    if (index > 3) scroll.scrollTo(((index - 3) * itemHeight).toInt())
  }
  val shape = MaterialTheme.shapes.small
  Popup(
      popupPositionProvider = BelowAnchor(gap, room),
      onDismissRequest = onDismiss,
      // Not clipped to the window: the shadow room may start left of the screen edge.
      properties = PopupProperties(focusable = true, clippingEnabled = false),
  ) {
    Box(Modifier.padding(ShadowRoom)) {
      Column(
          Modifier.width(154.dp)
              .heightIn(max = maxHeight)
              .dropShadow(
                  shape,
                  Shadow(
                      radius = 24.dp,
                      color = Ink.copy(alpha = 0.12f),
                      offset = DpOffset(0.dp, 8.dp),
                  ),
              )
              .background(Bg, shape)
              .border(1.dp, Border, shape)
              .clip(shape)
              .verticalScroll(scroll)
              .padding(horizontal = 5.dp, vertical = 9.dp)
      ) {
        options.forEach { option ->
          MenuItem(
              text = option,
              selected = option == selected,
              onClick = { onPick(option) },
              modifier = Modifier.testTag(optionTag(option)),
          )
        }
      }
    }
  }
}

/**
 * Puts the menu [gap] px below its anchor, left-aligned with it. The popup is [room] px larger than
 * the menu on every side (for the shadow), so it is shifted back by [room].
 */
private class BelowAnchor(private val gap: Int, private val room: Int) : PopupPositionProvider {
  override fun calculatePosition(
      anchorBounds: IntRect,
      windowSize: IntSize,
      layoutDirection: LayoutDirection,
      popupContentSize: IntSize,
  ): IntOffset = IntOffset(anchorBounds.left - room, anchorBounds.bottom + gap - room)
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
          .fillMaxWidth()
          .heightIn(min = 40.dp)
          .background(if (selected) Surface else Bg, RoundedCornerShape(8.dp))
          .selectable(selected = selected, onClick = onClick)
          .padding(start = 12.dp, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text,
        style =
            MaterialTheme.typography.bodyLarge
                .copy(
                    lineHeight = TextUnit.Unspecified,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                .exact(),
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
            MaterialTheme.typography.bodySmall
                .copy(fontWeight = FontWeight.Normal, lineHeight = 17.sp)
                .figmaLines(),
        color = Ink3,
        modifier = Modifier.padding(top = 1.dp).heightIn(min = 34.dp),
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
        style = MaterialTheme.typography.bodySmall.exact(),
        color = AccentText,
        modifier = Modifier.heightIn(min = 30.dp),
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
          .heightIn(min = 52.dp)
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
        style = MaterialTheme.typography.titleSmall.exact(),
        color =
            when {
              saving -> Ink
              enabled -> Bg
              else -> Ink3
            },
    )
  }
}

/** The first letters of the first and last words of [name], in capitals: "Alex Morel" → "AM". */
internal fun initialsOf(name: String): String {
  val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
  if (words.isEmpty()) return ""
  val first = words.first().first()
  val last = if (words.size > 1) words.last().first().toString() else ""
  return "$first$last".uppercase()
}
