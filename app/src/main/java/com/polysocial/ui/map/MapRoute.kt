// Contributors: OpenAI Codex (permission launcher and foreground location lifecycle for #51).
package com.polysocial.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Android permission UI boundary; device reads and permission history stay in the ViewModel. */
@Composable
fun MapRoute(onViewDetails: (String) -> Unit, viewModel: MapViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
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
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
      viewModel.onMapEntered()
    }
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
              Manifest.permission.ACCESS_FINE_LOCATION,
          )
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
      onTurnOnLocation = viewModel::turnOnLocation,
  )
}
