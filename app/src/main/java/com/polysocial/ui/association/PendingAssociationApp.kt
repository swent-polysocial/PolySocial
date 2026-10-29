// Contributors: OpenAI Codex (preserved navigation, logout and pending edits on rebase, #140).
// Contributors: OpenAI Codex (shared pending and verified identity controller, #140).
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
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.polysocial.R
import com.polysocial.ui.profile.ActivityLogout
import com.polysocial.ui.profile.OrganizationUiState
import com.polysocial.ui.profile.ProfileViewModel
import kotlinx.coroutines.launch

/** Route of Edit details, one level below the Organization tab's root. */
internal val EDIT_DETAILS_ROUTE = "${AssociationTab.ORGANIZATION.route}/edit"

/**
 * The app of an association account an admin hasn't verified yet (#127): its own tabs (Events, Map,
 * Organization) in their pending state, from the Figma "Association · pending" frames. It can't
 * publish, so the Events tab shows the review steps, and Drafts, New draft and Invite members say
 * they aren't available yet. The Map opens once it is verified. The Organization tab shows its
 * details, lets it edit them, and logs out like the students' Profile: the cache is cleared and the
 * account signed out (#109), then [onLoggedOut] returns to Welcome.
 *
 * Edit details keeps its form through a rotation. If the system ends the app while it is open, the
 * restored app reads the details again and Edit details closes, without the edits (not saved yet).
 *
 * @param editViewModel the Edit details form's ViewModel, created for each time it opens. Tests
 *   replace it, because the real one comes from Hilt.
 */
@Composable
fun PendingAssociationApp(
    onLoggedOut: () -> Unit,
    logoutViewModel: ProfileViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    editViewModel: @Composable () -> EditAssociationViewModel = { hiltViewModel() },
) {
  val organization by logoutViewModel.organization.collectAsStateWithLifecycle()
  val state =
      when (val current = organization) {
        OrganizationUiState.Loading -> PendingAssociationUiState.Loading
        is OrganizationUiState.Ready -> PendingAssociationUiState.Loaded(current.association)
        OrganizationUiState.Error -> PendingAssociationUiState.Failed
      }
  LaunchedEffect(logoutViewModel) { logoutViewModel.loadOrganization() }

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
            // dropUnlessResumed: a double tap doesn't open Edit details twice.
            onEdit = dropUnlessResumed { navController.navigate(EDIT_DETAILS_ROUTE) },
            onRetry = logoutViewModel::loadOrganization,
            onLogOut = logoutViewModel::requestLogout,
        )
      }
      composable(EDIT_DETAILS_ROUTE) { entry ->
        val leave = {
          if (navController.currentBackStackEntry == entry) navController.popBackStack()
        }
        val editing = (state as? PendingAssociationUiState.Loaded)?.association
        if (editing == null) {
          // Nothing to edit (the details aren't read): back to the tab, which says so.
          LaunchedEffect(Unit) { leave() }
        } else {
          EditAssociationRoute(
              association = editing,
              viewModel = editViewModel(),
              onBack = { leave() },
              onSaved = { saved ->
                logoutViewModel.onOrganizationSaved(saved)
                leave()
              },
          )
        }
      }
    }
  }
  ActivityLogout(logoutViewModel) {
    logoutViewModel.clearOrganization()
    AssociationTab.entries.forEach { navController.clearBackStack(it.route) }
    navController.popBackStack(AssociationTab.EVENTS.route, inclusive = true)
    onLoggedOut()
  }
}
