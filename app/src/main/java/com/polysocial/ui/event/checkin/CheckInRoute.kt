// Contributors: OpenAI Codex (GPT-6.2 Sol, high; permission UI for #469 under Lucas Pinto Ricardo’s
// supervision and approval).
package com.polysocial.ui.event.checkin

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.ui.common.openAppSettings

@Composable
internal fun CheckInRoute(
    entry: CheckInEntry,
    onBack: () -> Unit,
    onOpenGroup: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: CheckInViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val state by viewModel.state.collectAsStateWithLifecycle()
  val permissions =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          grants ->
        viewModel.onPermissionResult(
            precise = grants[ACCESS_FINE_LOCATION] == true,
            approximate = grants[ACCESS_COARSE_LOCATION] == true,
        )
      }
  LaunchedEffect(entry.target) { viewModel.open(entry.target) }
  DisposableEffect(viewModel, activity) {
    onDispose { if (activity?.isChangingConfigurations != true) viewModel.close() }
  }
  LifecycleStartEffect(viewModel) { onStopOrDispose { viewModel.onStop() } }
  BackHandler(onBack = onBack)
  CheckInScreen(
      entry = entry,
      state = state,
      modifier = modifier,
      onBack = onBack,
      onOpenGroup = onOpenGroup,
      onSettings = { context.openAppSettings() },
      onCheck = {
        val grant = ContextCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION)
        if (viewModel.onLocationAction(grant == PERMISSION_GRANTED)) {
          permissions.launch(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION))
        }
      },
  )
}
