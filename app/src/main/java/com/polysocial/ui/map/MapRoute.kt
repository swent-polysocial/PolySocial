// Contributors: OpenAI Codex (dismissal-aware location permission handling, #278).
// Contributors: OpenAI Codex (permission launcher and foreground location lifecycle for #51).
// Contributors: Claude (passes the "+" action to the map screen, #110; the search and filters,
// #116; the Map tab's ViewModel for each account type, #133).
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
import com.polysocial.model.user.AccountType

/**
 * The Map tab's ViewModel for [accountType]: a verified association's own upcoming events, private
 * ones included (#133), or the students' public events.
 */
@Composable
fun mapViewModel(accountType: AccountType): MapViewModel =
    if (accountType == AccountType.ASSOCIATION) hiltViewModel<AssociationMapViewModel>()
    else hiltViewModel<StudentMapViewModel>()

/** Android permission UI boundary; device reads and permission history stay in the ViewModel. */
@Composable
fun MapRoute(
    onViewDetails: (String) -> Unit,
    onCreateEvent: (() -> Unit)? = null,
    viewModel: MapViewModel = hiltViewModel<StudentMapViewModel>(),
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
        viewModel.onPermissionResult(it[Manifest.permission.ACCESS_COARSE_LOCATION])
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
      onCreateEvent = onCreateEvent,
      tokenConfigured = tokenConfigured,
      renderer = renderer,
      filters =
          MapFilterActions(
              onSearchChange = viewModel::setSearch,
              onSelectDate = viewModel::selectDate,
              onToggleCategory = viewModel::toggleCategory,
              onSelectDistance = viewModel::selectDistance,
              onClearFilters = viewModel::clearFilters,
              onClearAll = viewModel::clearSearchAndFilters,
          ),
      today = viewModel.today,
  )
}
