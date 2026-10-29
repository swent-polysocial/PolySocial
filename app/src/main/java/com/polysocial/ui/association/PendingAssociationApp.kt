// Contributors: Claude (the app of an association an admin hasn't verified yet, #127; nothing kept
// from one session to the next, after a review, #127; its logout moved to the shared
// ActivityLogout, #208).
package com.polysocial.ui.association

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.polysocial.R
import com.polysocial.ui.profile.ActivityLogout
import com.polysocial.ui.profile.ProfileViewModel
import kotlinx.coroutines.launch

/**
 * The app of an association account an admin hasn't verified yet (#127): its own tabs (Events, Map,
 * Organization) in their pending state, from the Figma "Association · pending" frames. It can't
 * publish, so the Events tab shows the review steps, and Drafts, New draft and Invite members say
 * they aren't available yet. The Map opens once it is verified. The Organization tab shows its
 * details and logs out like the students' Profile: the cache is cleared and the account signed out
 * (#109), then [onLoggedOut] returns to Welcome.
 */
@Composable
fun PendingAssociationApp(
    onLoggedOut: () -> Unit,
    viewModel: PendingAssociationViewModel = hiltViewModel(),
    logoutViewModel: ProfileViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // This app's ViewModels belong to the activity, which outlives the session: each session reads
  // its own details, and Log out leaves nothing (details, logout state, tab stacks) to the next
  // account.
  LaunchedEffect(viewModel) { viewModel.open() }

  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val notAvailable = stringResource(R.string.association_not_available_yet)
  val showNotAvailable: () -> Unit = {
    // A second tap replaces the message instead of queueing another one.
    snackbarHostState.currentSnackbarData?.dismiss()
    scope.launch { snackbarHostState.showSnackbar(notAvailable) }
  }
  val association = (state as? PendingAssociationUiState.Loaded)?.association

  AssociationShell(navController, snackbarHostState) {
    navigation(
        startDestination = AssociationTab.EVENTS.rootRoute,
        route = AssociationTab.EVENTS.route,
    ) {
      composable(AssociationTab.EVENTS.rootRoute) {
        PendingEventsScreen(association, onNotAvailable = showNotAvailable)
      }
    }
    navigation(startDestination = AssociationTab.MAP.rootRoute, route = AssociationTab.MAP.route) {
      composable(AssociationTab.MAP.rootRoute) { PendingMapScreen(association) }
    }
    navigation(
        startDestination = AssociationTab.ORGANIZATION.rootRoute,
        route = AssociationTab.ORGANIZATION.route,
    ) {
      composable(AssociationTab.ORGANIZATION.rootRoute) {
        PendingOrganizationScreen(
            state = state,
            onRetry = viewModel::retry,
            onLogOut = logoutViewModel::requestLogout,
        )
      }
    }
  }
  ActivityLogout(logoutViewModel) {
    viewModel.close()
    AssociationTab.entries.forEach { navController.clearBackStack(it.route) }
    navController.popBackStack(AssociationTab.EVENTS.route, inclusive = true)
    onLoggedOut()
  }
}
