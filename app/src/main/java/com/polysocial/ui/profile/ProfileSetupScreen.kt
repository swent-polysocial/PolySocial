// Contributors: Claude (profile step screen for #34, built from the Figma "First proposal
// revamped" Profile frames; layout that grows with large text, one save report and picker
// accessibility after review; plain Column layout after review; exact text from the theme, and
// Back locked while saving, #74; generic save error with Log out, #142; full name limit, #143, as a
// plural after review).
// Contributors: Claude (the shared field label, #251; split into the details and interests screens,
// with a shared frame, heading, top bar and button, #230; the button's role, after review, #230).
package com.polysocial.ui.profile

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.polysocial.R
import com.polysocial.model.user.MAX_DISPLAY_NAME_LENGTH
import com.polysocial.model.user.SECTIONS
import com.polysocial.model.user.YEARS
import com.polysocial.ui.common.FieldLabel
import com.polysocial.ui.theme.Accent
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
import com.polysocial.ui.theme.exact
import kotlin.math.roundToInt

/** Test tags of the profile step. */
object ProfileSetupTestTags {
  const val SCREEN = "profile_setup_screen"
  /** The details screen (step 1 of 2). */
  const val DETAILS = "profile_setup_details"
  /** The interests screen (step 2 of 2, #230). */
  const val INTERESTS = "profile_setup_interests"
  const val BACK = "profile_setup_back"
  const val FULL_NAME = "profile_setup_full_name"
  const val EMAIL = "profile_setup_email"
  const val SECTION = "profile_setup_section"
  const val YEAR = "profile_setup_year"
  const val INTERESTS_COUNT = "profile_setup_interests_count"
  const val ERROR = "profile_setup_error"
  const val NAME_ERROR = "profile_setup_name_error"
  const val LOG_OUT = "profile_setup_log_out"
  const val CONTINUE = "profile_setup_continue"
  const val FINISH = "profile_setup_finish"

  fun sectionOption(code: String) = "profile_setup_section_$code"

  fun yearOption(code: String) = "profile_setup_year_$code"

  fun interest(code: String) = "profile_setup_interest_$code"
}

/**
 * The profile step after email verification: the details, then the interests (#230), whose Finish
 * saves the profile. Reports where to go next through the callbacks, so the app's navigation (#74)
 * decides how to get there. [onProfileSaved] is called once per visit, when the profile exists: the
 * caller must then leave this step and remove it from the back stack, or the user could come back
 * to a finished, locked form. On the details, Back calls [onBack]; on the interests, Back (the
 * arrow and the system button) returns to the details. Neither does anything while the profile is
 * being saved or is saved, so the step stays on screen until [onProfileSaved] has run. "Log out" on
 * the generic save error (#142) also calls [onBack], since it is the same sign-out.
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
  // While saving or saved, leaving would drop the save's result before the app has used it.
  BackHandler(enabled = state.locked) {}
  BackHandler(enabled = state.page == ProfileSetupPage.Interests && !state.locked) {
    viewModel.onBackToDetails()
  }
  when (state.page) {
    ProfileSetupPage.Details ->
        ProfileSetupContent(
            state = state,
            onBack = onBack,
            onDisplayNameChange = viewModel::onDisplayNameChange,
            onSectionChange = viewModel::onSectionChange,
            onYearChange = viewModel::onYearChange,
            onContinue = viewModel::onContinue,
        )
    ProfileSetupPage.Interests ->
        ProfileInterestsContent(
            state = state,
            onBack = viewModel::onBackToDetails,
            onInterestToggle = viewModel::onInterestToggle,
            onFinish = viewModel::onFinish,
            // Logging out is what Back does on the details: it signs out, and logging in again gets
            // a fresh token.
            onLogOut = onBack,
        )
  }
}

/**
 * Draws step 1 of the profile step, the details, for [state]; all measurements come from the Figma
 * "Profile · …" frames. Continue opens the interests and saves nothing, so this screen has no save
 * states.
 */
@Composable
fun ProfileSetupContent(
    state: ProfileSetupUiState,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSectionChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
  ProfileStepScaffold(
      pageTag = ProfileSetupTestTags.DETAILS,
      top = {
        ProfileTopBar(onBack, backEnabled = true, step = 1)
        Spacer(Modifier.height(11.5.dp))
        StepHeading(R.string.profile_title, R.string.profile_subtitle)
        Spacer(Modifier.height(17.dp))
        Initials(state.displayName)
        Spacer(Modifier.height(15.5.dp))
        MeasuredFieldLabel(R.string.profile_full_name)
        Spacer(Modifier.height(5.5.dp))
        NameField(state.displayName, onDisplayNameChange, tooLong = state.nameTooLong)
        if (state.nameTooLong) NameTooLong()
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
              fieldTag = ProfileSetupTestTags.YEAR,
              optionTag = ProfileSetupTestTags::yearOption,
              textStart = 13.dp,
              modifier = Modifier.weight(1f),
          )
        }
      },
      bottom = {
        PrivacyNote()
        Spacer(Modifier.height(11.dp))
        StepButton(
            label = R.string.profile_continue,
            saving = false,
            enabled = state.canContinue,
            tag = ProfileSetupTestTags.CONTINUE,
            onClick = onContinue,
        )
      },
  )
}

/**
 * The frame both screens of the profile step share: [top] from the top of the screen, and [bottom]
 * at the bottom, 28 dp above the edge as in Figma. On a short screen, with large text or the
 * keyboard open, the whole screen scrolls so the button stays reachable; otherwise the free space
 * sits between the two. [pageTag] tags the screen shown.
 */
@Composable
internal fun ProfileStepScaffold(
    pageTag: String,
    top: @Composable ColumnScope.() -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
  Box(Modifier.fillMaxSize().background(Bg).testTag(ProfileSetupTestTags.SCREEN)) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Column(
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .heightIn(min = maxHeight)
              .padding(horizontal = 20.dp)
              .testTag(pageTag),
          verticalArrangement = Arrangement.SpaceBetween,
      ) {
        // The gaps are the Figma frame's; a taller element (larger font size) pushes the ones below
        // it down.
        Column(Modifier.fillMaxWidth(), content = top)
        Column(Modifier.fillMaxWidth()) {
          // Keeps a gap above the bottom group when the screen scrolls; hidden in the free space
          // otherwise.
          Spacer(Modifier.height(24.dp))
          bottom()
          Spacer(Modifier.height(28.dp))
        }
      }
    }
  }
}

/** The screen's title and the line under it, as in both steps' Figma frames. */
@Composable
internal fun StepHeading(@StringRes title: Int, @StringRes subtitle: Int) {
  Text(
      stringResource(title),
      style = MaterialTheme.typography.headlineMedium.figmaLines(),
      color = Ink,
      modifier = Modifier.heightIn(min = 32.dp),
  )
  Spacer(Modifier.height(7.5.dp))
  Text(
      stringResource(subtitle),
      style = MaterialTheme.typography.bodyLarge.figmaLines(),
      color = Ink2,
      // Figma's text box is narrower than the column, which sets where the line breaks.
      modifier = Modifier.widthIn(max = 293.5.dp).fillMaxWidth().heightIn(min = 44.dp),
  )
}

/**
 * Back, then "Step [step] of 2" and the two progress bars, filled up to [step]. The back arrow is a
 * 22 dp icon at (19, 23), inside a 44 dp touch target; the step text (y 26) and the bars (y 32) end
 * at the right margin.
 */
@Composable
internal fun ProfileTopBar(onBack: () -> Unit, backEnabled: Boolean, step: Int) {
  Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
    Box(
        Modifier.offset(x = (-12).dp)
            .size(44.dp)
            .clickable(enabled = backEnabled, onClick = onBack)
            .testTag(ProfileSetupTestTags.BACK),
        contentAlignment = Alignment.Center,
    ) {
      Image(painterResource(R.drawable.ic_back), stringResource(R.string.profile_back))
    }
    Spacer(Modifier.weight(1f))
    Text(
        stringResource(R.string.profile_step, step),
        style = MaterialTheme.typography.bodySmall.exact(),
        color = Ink2,
        modifier = Modifier.padding(top = 14.dp),
    )
    Spacer(Modifier.width(9.5.dp))
    ProgressBar(Ink)
    Spacer(Modifier.width(4.dp))
    ProgressBar(if (step == 2) Ink else Border)
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

/** Figma's field label, in a 17 dp box so the field below starts exactly where Figma's does. */
@Composable
private fun MeasuredFieldLabel(@StringRes text: Int, modifier: Modifier = Modifier) {
  FieldLabel(
      text,
      modifier.heightIn(min = 17.dp),
      MaterialTheme.typography.labelMedium.exact(),
  )
}

/** The field text style: Body with Figma's 20 px line height inside fields. */
@Composable
private fun fieldTextStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp).exact()

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, tooLong: Boolean) {
  val shape = MaterialTheme.shapes.small
  val tooLongMessage =
      pluralStringResource(
          R.plurals.full_name_too_long,
          MAX_DISPLAY_NAME_LENGTH,
          MAX_DISPLAY_NAME_LENGTH,
      )
  BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      textStyle = fieldTextStyle().copy(color = Ink),
      cursorBrush = SolidColor(Ink),
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
      modifier =
          Modifier.fillMaxWidth()
              .semantics { if (tooLong) error(tooLongMessage) }
              .testTag(ProfileSetupTestTags.FULL_NAME),
      decorationBox = { innerTextField ->
        Box(
            Modifier.fillMaxWidth()
                .heightIn(min = 50.dp)
                .background(Bg, shape)
                // Figma "Profile · name too long": a 2 dp border in the error red.
                .border(if (tooLong) 2.dp else 1.dp, if (tooLong) Accent else Border, shape)
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
    MeasuredFieldLabel(R.string.profile_email)
    Spacer(Modifier.weight(1f))
    Image(painterResource(R.drawable.ic_check_small), contentDescription = null)
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
    MeasuredFieldLabel(label, Modifier.semantics { hideFromAccessibility() })
    Spacer(Modifier.height(5.5.dp))
    Box(Modifier.onGloballyPositioned { fieldBottom = it.boundsInWindow().bottom.roundToInt() }) {
      Row(
          Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .background(Bg, shape)
              .border(if (expanded) 2.dp else 1.dp, if (expanded) Ink else Border, shape)
              .clickable(role = Role.DropdownList) { expanded = true }
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
          painterResource(R.drawable.ic_check_small),
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

/**
 * Figma "Profile · name too long" (#143): the error icon and "Use at most 50 characters" in red, 7
 * dp under the name field, which pushes the fields below down.
 */
@Composable
private fun NameTooLong() {
  Row(
      Modifier.padding(top = 7.dp).testTag(ProfileSetupTestTags.NAME_ERROR),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Image(
        painterResource(R.drawable.ic_error),
        contentDescription = null,
        modifier = Modifier.padding(top = 1.dp),
    )
    Text(
        pluralStringResource(
            R.plurals.full_name_too_long,
            MAX_DISPLAY_NAME_LENGTH,
            MAX_DISPLAY_NAME_LENGTH,
        ),
        style = MaterialTheme.typography.bodySmall.exact(),
        color = AccentText,
    )
  }
}

/**
 * The step's dark button ([label]): greyed out unless [enabled], and while [saving] white with a
 * border, a spinner and "Saving…", as in the Figma saving frame.
 */
@Composable
internal fun StepButton(
    @StringRes label: Int,
    saving: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
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
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(tag),
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
        stringResource(if (saving) R.string.profile_saving else label),
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
