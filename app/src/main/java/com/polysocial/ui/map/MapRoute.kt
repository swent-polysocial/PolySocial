// Contributors: OpenAI Codex (permission launcher and foreground location lifecycle for #51).
package com.polysocial.ui.map

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.Event

/** Android permission UI boundary; device reads and permission history stay in the ViewModel. */
@Composable
fun MapRoute(
    onViewDetails: (String) -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
    tokenConfigured: Boolean = stringResource(R.string.mapbox_access_token).startsWith("pk."),
    permissionRationale: (() -> Boolean)? = null,
    renderer: @Composable (List<Event>, (String) -> Unit, (MapRenderStatus) -> Unit, Dp) -> Unit =
        defaultMapRenderer,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  val activity = LocalActivity.current
  val context = LocalContext.current
  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionResult()
      }
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> viewModel.onMapEntered()
        Lifecycle.Event.ON_PAUSE -> viewModel.onMapInactive()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.onMapInactive()
    }
  }
  LaunchedEffect(state.locationState) {
    if (state.locationState == MapLocationState.RequestPermission) {
      viewModel.onPermissionRequested()
      permissionLauncher.launch(
          arrayOf(
              Manifest.permission.ACCESS_COARSE_LOCATION,
          )
      )
    } else if (state.locationState == MapLocationState.OpenSettings) {
      viewModel.onSettingsOpened()
      context.startActivity(
          Intent(
                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.fromParts("package", context.packageName, null),
              )
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    }
  }
  MapScreen(
      state = state,
      onSelectEvent = viewModel::selectEvent,
      onClosePreview = viewModel::closePreview,
      onViewDetails = onViewDetails,
      onRetry = viewModel::retry,
      onRenderStatus = viewModel::onRenderStatus,
      onTurnOnLocation = {
        viewModel.turnOnLocation(
            permissionRationale?.invoke()
                ?: (activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
        )
      },
      tokenConfigured = tokenConfigured,
      renderer = renderer,
  )
}
