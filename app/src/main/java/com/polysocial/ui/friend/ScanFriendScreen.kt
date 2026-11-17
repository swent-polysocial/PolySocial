// Contributors: Claude Opus 5.5 (drafted Scan a friend's code from Figma section 15 and its #359
// states); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.common.interestLabel
import com.polysocial.ui.common.openAppSettings
import com.polysocial.ui.group.initials
import com.polysocial.ui.profile.PrivacyHeader
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.profile.profileDetails
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.WarningSoft

/** Whether the scanner may use the camera. */
enum class CameraAccess {
  /** Not granted yet: the system dialog is up, or about to be. */
  ASKING,
  GRANTED,
  DENIED,
}

/**
 * Navigation boundary for Scan a friend's code (#359): asks for the camera when it opens, never
 * before; a refusal shows "Camera access is off", and the scanner comes back by itself once the
 * permission is granted in Settings. Close, Back and "Back to my code" call [onBack].
 */
@Composable
fun ScanFriendRoute(
    onBack: () -> Unit,
    viewModel: ScanFriendViewModel = hiltViewModel(),
    scanner: QrScanner = CameraQrScanner,
) {
  val context = LocalContext.current
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var granted by rememberSaveable { mutableStateOf(context.hasCameraPermission()) }
  var asked by rememberSaveable { mutableStateOf(false) }
  val request =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
      }
  LaunchedEffect(Unit) { if (!granted && !asked) request.launch(Manifest.permission.CAMERA) }
  // Back from Settings: the camera may have been allowed there.
  LifecycleResumeEffect(Unit) {
    granted = context.hasCameraPermission()
    onPauseOrDispose {}
  }
  BackHandler(onBack = onBack)
  ScanFriendScreen(
      state = state,
      access =
          when {
            granted -> CameraAccess.GRANTED
            asked -> CameraAccess.DENIED
            else -> CameraAccess.ASKING
          },
      scanner = scanner,
      onClose = onBack,
      onCode = viewModel::onCodeScanned,
      onScanAgain = viewModel::onScanAgain,
      onButton = viewModel::onButton,
      onOpenSettings = { context.openAppSettings() },
  )
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Scan a friend's code, as in Figma section 15 (#336) and its #359 states: the camera, "Camera
 * access is off", a sheet over the paused camera when a scan opens no card, or the scanned
 * student's public profile with the button their friendship gives.
 */
@Composable
fun ScanFriendScreen(
    state: ScanFriendUiState,
    access: CameraAccess,
    scanner: QrScanner,
    onClose: () -> Unit,
    onCode: (String) -> Unit,
    onScanAgain: () -> Unit,
    onButton: () -> Unit,
    onOpenSettings: () -> Unit,
) {
  Box(Modifier.fillMaxSize().testTag(C.Tag.scan_friend_screen)) {
    when {
      state is ScanFriendUiState.Card -> FriendCard(state, onClose, onButton)
      access == CameraAccess.DENIED -> CameraOff(onClose, onOpenSettings)
      else -> CameraView(state, access == CameraAccess.GRANTED, scanner, onClose, onCode)
    }
    if (state is ScanFriendUiState.Problem && access != CameraAccess.DENIED) {
      ProblemSheet(state.problem, onScanAgain, onClose)
    }
  }
}

/** Figma "Scan a friend's code · Camera": the dark scanner with its frame and torch. */
@Composable
private fun CameraView(
    state: ScanFriendUiState,
    granted: Boolean,
    scanner: QrScanner,
    onClose: () -> Unit,
    onCode: (String) -> Unit,
) {
  var torchOn by rememberSaveable { mutableStateOf(false) }
  Column(
      Modifier.fillMaxSize().background(Ink),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    ScanHeader(R.string.scan_friend_title, onClose, dark = true)
    // Scrolls in a short window (landscape), so the torch below always stays on screen.
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(128.dp))
      Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        if (granted) {
          scanner.Camera(
              scanning = state == ScanFriendUiState.Scanning,
              torchOn = torchOn,
              onCode = onCode,
              modifier =
                  Modifier.fillMaxSize()
                      .clip(RoundedCornerShape(24.dp))
                      .testTag(C.Tag.scan_friend_camera),
          )
        } else {
          Box(Modifier.fillMaxSize().background(CameraSurface, RoundedCornerShape(24.dp)))
        }
        ViewfinderCorners()
        if (state == ScanFriendUiState.Loading) LoadingOverlay()
      }
      Spacer(Modifier.height(27.dp))
      Text(
          stringResource(R.string.scan_friend_heading),
          color = Bg,
          style = privacyTextStyle(20, 25.2f, FontWeight.ExtraBold),
          modifier = Modifier.semantics { heading() },
      )
      Spacer(Modifier.height(12.dp))
      Text(
          stringResource(R.string.scan_friend_hint),
          color = CameraHint,
          textAlign = TextAlign.Center,
          style = privacyTextStyle(14, 21f),
          modifier = Modifier.padding(horizontal = 30.dp),
      )
    }
    Spacer(Modifier.height(16.dp))
    TorchButton(torchOn) { torchOn = !torchOn }
    Spacer(Modifier.height(31.dp))
  }
}

/** The frame's four white corners and Figma's red scan line, drawn over the camera. */
@Composable
private fun BoxScope.ViewfinderCorners() {
  Canvas(Modifier.matchParentSize()) {
    val stroke = 3.dp.toPx()
    val arm = 46.dp.toPx()
    val radius = 24.dp.toPx()
    val inset = -stroke / 2
    val right = size.width - inset
    val bottom = size.height - inset
    for ((corner, start, sweep) in
        listOf(
            Triple(Offset(inset, inset), 180f, 90f),
            Triple(Offset(right - radius * 2, inset), 270f, 90f),
            Triple(Offset(inset, bottom - radius * 2), 90f, 90f),
            Triple(Offset(right - radius * 2, bottom - radius * 2), 0f, 90f),
        )) {
      drawArc(
          Bg,
          start,
          sweep,
          useCenter = false,
          topLeft = corner,
          size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
          style = Stroke(stroke, cap = StrokeCap.Round),
      )
    }
    val armEnd = arm - radius
    drawLine(
        Bg,
        Offset(inset + radius, inset),
        Offset(inset + radius + armEnd, inset),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Bg,
        Offset(right - radius, inset),
        Offset(right - radius - armEnd, inset),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Bg,
        Offset(inset + radius, bottom),
        Offset(inset + radius + armEnd, bottom),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Bg,
        Offset(right - radius, bottom),
        Offset(right - radius - armEnd, bottom),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Bg,
        Offset(inset, inset + radius),
        Offset(inset, inset + radius + armEnd),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Bg,
        Offset(right, inset + radius),
        Offset(right, inset + radius + armEnd),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Bg,
        Offset(inset, bottom - radius),
        Offset(inset, bottom - radius - armEnd),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Bg,
        Offset(right, bottom - radius),
        Offset(right, bottom - radius - armEnd),
        stroke,
        StrokeCap.Round,
    )
    val lineY = size.height / 2
    drawLine(
        ScanLine,
        Offset(20.dp.toPx(), lineY),
        Offset(size.width - 20.dp.toPx(), lineY),
        2.dp.toPx(),
    )
  }
}

@Composable
private fun BoxScope.LoadingOverlay() {
  val description = stringResource(R.string.scan_friend_loading)
  Box(
      Modifier.matchParentSize()
          .background(Ink.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
          .testTag(C.Tag.scan_friend_loading)
          .semantics { contentDescription = description },
      contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(color = Bg, strokeWidth = 3.dp)
  }
}

/** Figma's round Torch button under its label. */
@Composable
private fun TorchButton(on: Boolean, onToggle: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        Modifier.size(56.dp)
            .clip(CircleShape)
            .background(if (on) Bg else CameraSurface)
            .clickable(role = Role.Switch, onClick = onToggle)
            .testTag(C.Tag.scan_friend_torch),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_torch),
          contentDescription = stringResource(R.string.scan_friend_torch),
          tint = if (on) Ink else Bg,
          modifier = Modifier.size(22.dp),
      )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.scan_friend_torch),
        color = CameraHint,
        style = privacyTextStyle(12, 15.1f),
    )
  }
}

/** The title row: Close and the title, white on the camera, ink on white screens. */
@Composable
private fun ScanHeader(@StringRes title: Int, onClose: () -> Unit, dark: Boolean) {
  PrivacyHeader(
      title,
      C.Tag.scan_friend_screen + "_title",
      onClose,
      icon = R.drawable.ic_close,
      iconDescription = R.string.scan_friend_close,
      backTag = C.Tag.scan_friend_close,
      color = if (dark) Bg else Ink,
  )
}

/** Figma "Scan a friend's code · Camera denied": why scanning can't start, and the way out. */
@Composable
private fun CameraOff(onClose: () -> Unit, onOpenSettings: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Bg).testTag(C.Tag.scan_friend_camera_off)) {
    ScanHeader(R.string.scan_friend_title, onClose, dark = false)
    Column(
        Modifier.weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(150.dp))
      Box(
          Modifier.size(64.dp).background(WarningSoft, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        androidx.compose.foundation.Image(
            painterResource(R.drawable.ic_camera),
            null,
            Modifier.size(28.dp),
        )
      }
      Spacer(Modifier.height(20.dp))
      Title(R.string.scan_friend_camera_off_title, TextAlign.Center)
      Spacer(Modifier.height(12.dp))
      Body(stringResource(R.string.scan_friend_camera_off_body), TextAlign.Center)
    }
    Actions(
        primary = R.string.scan_friend_open_settings,
        primaryTag = C.Tag.scan_friend_open_settings,
        onPrimary = onOpenSettings,
        onBack = onClose,
    )
  }
}

/** Figma's sheet over the paused camera when a scan opens no card (#336 and #359's states). */
@Composable
private fun BoxScope.ProblemSheet(
    problem: ScanProblem,
    onScanAgain: () -> Unit,
    onBack: () -> Unit,
) {
  Box(Modifier.matchParentSize().background(Ink.copy(alpha = 0.6f)))
  Column(
      Modifier.align(Alignment.BottomCenter)
          .fillMaxWidth()
          .background(Bg, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
          .testTag(C.Tag.scan_friend_problem),
  ) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
      Box(Modifier.size(width = 36.dp, height = 4.dp).background(Border, RoundedCornerShape(2.dp)))
      Spacer(Modifier.height(13.dp))
      Icon(painterResource(R.drawable.ic_alert), null, Modifier.size(20.dp), tint = AccentText)
      Spacer(Modifier.height(13.dp))
      Title(problem.title, TextAlign.Start, Modifier.testTag(C.Tag.scan_friend_problem_title))
      Spacer(Modifier.height(13.dp))
      Body(stringResource(problem.body), TextAlign.Start)
    }
    Actions(
        R.string.scan_friend_scan_again,
        C.Tag.scan_friend_scan_again,
        onScanAgain,
        onBack,
        top = 20.dp,
    )
  }
}

private val ScanProblem.title: Int
  get() =
      when (this) {
        ScanProblem.INVALID_CODE -> R.string.scan_friend_invalid_title
        ScanProblem.OWN_CODE -> R.string.scan_friend_own_title
        ScanProblem.NOT_FOUND -> R.string.scan_friend_not_found_title
        ScanProblem.COULD_NOT_LOAD -> R.string.scan_friend_not_loaded_title
      }

private val ScanProblem.body: Int
  get() =
      when (this) {
        ScanProblem.INVALID_CODE -> R.string.scan_friend_invalid_body
        ScanProblem.OWN_CODE -> R.string.scan_friend_own_body
        ScanProblem.NOT_FOUND -> R.string.scan_friend_not_found_body
        ScanProblem.COULD_NOT_LOAD -> R.string.scan_friend_not_loaded_body
      }

/** Figma's two stacked buttons: a dark primary one, then "Back to my code" outlined. */
@Composable
private fun Actions(
    @StringRes primary: Int,
    primaryTag: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    top: androidx.compose.ui.unit.Dp = 0.dp,
) {
  Column(Modifier.padding(start = 20.dp, end = 20.dp, top = top, bottom = 28.dp)) {
    PrimaryButton(stringResource(primary), enabled = true, tag = primaryTag, onClick = onPrimary)
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier.fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(BorderStroke(1.dp, Border), RoundedCornerShape(26.dp))
            .background(Bg)
            .clickable(role = Role.Button, onClick = onBack)
            .testTag(C.Tag.scan_friend_back_to_my_code),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          stringResource(R.string.scan_friend_back_to_my_code),
          style = MaterialTheme.typography.titleSmall,
          color = Ink,
      )
    }
  }
}

/** Figma "Scanned student · Public profile" and its #359 button states. */
@Composable
private fun FriendCard(card: ScanFriendUiState.Card, onClose: () -> Unit, onButton: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Bg).testTag(C.Tag.scan_friend_card)) {
    ScanHeader(R.string.scan_friend_profile_title, onClose, dark = false)
    Column(
        Modifier.weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
      Spacer(Modifier.height(16.dp))
      CardHeader(card)
      Spacer(Modifier.height(30.dp))
      CardInterests(card)
    }
    CardAction(card, onButton)
  }
}

@Composable
private fun CardHeader(card: ScanFriendUiState.Card) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
        Modifier.size(60.dp).background(SuccessSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          initials(card.profile.displayName),
          color = Success,
          style = privacyTextStyle(20, 16.4f, FontWeight.Bold),
      )
    }
    Spacer(Modifier.width(14.dp))
    Column {
      Text(
          card.profile.displayName,
          color = Ink,
          style = privacyTextStyle(22, 27.7f, FontWeight.ExtraBold),
          modifier = Modifier.testTag(C.Tag.scan_friend_name).semantics { heading() },
      )
      Text(
          profileDetails(card.profile.section, card.profile.year),
          color = Ink2,
          style = privacyTextStyle(14, 18f),
          modifier = Modifier.testTag(C.Tag.scan_friend_details),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardInterests(card: ScanFriendUiState.Card) {
  val interests = card.profile.interests.mapNotNull(::interestLabel)
  if (interests.isEmpty()) return
  Text(
      stringResource(R.string.scan_friend_interests),
      color = Ink,
      style = privacyTextStyle(16, 20f, FontWeight.Bold),
  )
  Spacer(Modifier.height(8.dp))
  FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    for (interest in interests) {
      Text(
          stringResource(interest),
          color = Ink,
          style = privacyTextStyle(13, 16.4f, FontWeight.SemiBold),
          modifier =
              Modifier.heightIn(min = 32.dp)
                  .border(1.dp, Border, RoundedCornerShape(16.dp))
                  .padding(horizontal = 13.dp, vertical = 8.dp)
                  .testTag(C.Tag.scan_friend_interest),
      )
    }
  }
}

/** The note above the button (the friendship, or why the last tap failed) and the button. */
@Composable
private fun CardAction(card: ScanFriendUiState.Card, onButton: () -> Unit) {
  val firstName = card.profile.displayName.trim().substringBefore(' ')
  val note =
      when {
        card.error == FriendActionError.OFFLINE -> stringResource(R.string.scan_friend_offline)
        card.error == FriendActionError.FAILED -> stringResource(R.string.scan_friend_failed)
        card.button == FriendButton.SENT ->
            stringResource(R.string.scan_friend_sent_note, firstName)
        card.button == FriendButton.ACCEPT ->
            stringResource(R.string.scan_friend_accept_note, firstName)
        card.button == FriendButton.FRIENDS ->
            stringResource(R.string.scan_friend_friends_note, firstName)
        else -> null
      }
  Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
    if (note != null) {
      Text(
          note,
          color = if (card.error != null) AccentText else Ink2,
          textAlign = TextAlign.Center,
          style = privacyTextStyle(13, 16.4f),
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.scan_friend_note),
      )
      Spacer(Modifier.height(18.dp))
    }
    PrimaryButton(
        stringResource(card.button.label),
        enabled = card.button.actionable && !card.busy,
        loading = card.busy,
        tag = C.Tag.scan_friend_button,
        onClick = onButton,
    )
  }
}

private val FriendButton.label: Int
  get() =
      when (this) {
        FriendButton.ADD -> R.string.scan_friend_add
        FriendButton.SENT -> R.string.scan_friend_sent
        FriendButton.ACCEPT -> R.string.scan_friend_accept
        FriendButton.FRIENDS -> R.string.scan_friend_friends
      }

private val FriendButton.actionable: Boolean
  get() = this == FriendButton.ADD || this == FriendButton.ACCEPT

@Composable
private fun Title(@StringRes text: Int, align: TextAlign, modifier: Modifier = Modifier) {
  Text(
      stringResource(text),
      color = Ink,
      textAlign = align,
      style = privacyTextStyle(22, 27.7f, FontWeight.ExtraBold),
      modifier = modifier.fillMaxWidth().semantics { heading() },
  )
}

@Composable
private fun Body(text: String, align: TextAlign) {
  Text(
      text,
      color = Ink2,
      textAlign = align,
      style = privacyTextStyle(14, 18f),
      modifier = Modifier.fillMaxWidth(),
  )
}

/** Figma's camera surfaces: the empty preview and the torch's circle. */
private val CameraSurface = Color(0xFF2A2A31)

/** Figma's hint and Torch label on the camera. */
private val CameraHint = Color(0xFFC9C9D1)

/** Figma's red scan line. */
private val ScanLine = Color(0xFFFF6B72)
