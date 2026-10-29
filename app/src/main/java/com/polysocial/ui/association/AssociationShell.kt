// Contributors: OpenAI Codex (shared verified and pending navigation, #140).
// Contributors: Claude (the association's bottom bar and tab graphs, from the Figma "Association"
// frames, #127).
package com.polysocial.ui.association

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.PlusJakartaSans
import com.polysocial.ui.theme.exact

/**
 * The tabs of an association account, in the order of its bottom bar (Figma section 02): no Chats
 * or Profile, since associations have no access to students' profiles, groups or chats.
 *
 * @property route Navigation route of the tab's own graph, which holds its back stack.
 */
enum class AssociationTab(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val navItemTag: String,
) {
  EVENTS(
      "association_events",
      R.string.association_tab_events,
      R.drawable.ic_tab_events,
      AssociationTags.NAV_EVENTS,
  ),
  MAP(
      "association_map",
      R.string.association_tab_map,
      R.drawable.ic_tab_map,
      AssociationTags.NAV_MAP,
  ),
  ORGANIZATION(
      "association_organization",
      R.string.association_tab_organization,
      R.drawable.ic_tab_organization,
      AssociationTags.NAV_ORGANIZATION,
  );

  /** Route of the tab's root screen, the start destination of its graph. */
  val rootRoute: String
    get() = "$route/root"
}

/**
 * The frame of an association's app: the current tab's screen above the association's bottom bar
 * (Events, Map, Organization), and [snackbarHostState]'s Snackbar above the bar. [tabGraphs] adds
 * one nested graph per [AssociationTab] (route [AssociationTab.route], start
 * [AssociationTab.rootRoute]), so each tab keeps its own back stack. The bar shows on the tabs'
 * root screens only: a screen opened from a tab (Edit details) is full screen, as in Figma.
 *
 * Both pending and verified associations supply their tab graphs. Verified navigation may supply
 * existing graph routes, its top bar, and explicit bar visibility for full-screen destinations.
 *
 * @param routes maps each association tab to its graph and root routes.
 * @param showBottomBar overrides the root-only rule when the verified app supplies its own routes.
 * @param dismissSnackbarOnNavigation keeps pending notices scoped to the screen that raised them.
 */
@Composable
fun AssociationShell(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    routes: Map<AssociationTab, Pair<String, String>> =
        AssociationTab.entries.associateWith { it.route to it.rootRoute },
    showBottomBar: Boolean? = null,
    topBar: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    useAppTags: Boolean = false,
    dismissSnackbarOnNavigation: Boolean = true,
    tabGraphs: NavGraphBuilder.() -> Unit,
) {
  val entry by navController.currentBackStackEntryAsState()
  val destination = entry?.destination
  val currentTab =
      AssociationTab.entries.firstOrNull { tab ->
        destination?.hierarchy?.any { it.route == routes.getValue(tab).first } == true
      } ?: AssociationTab.EVENTS
  val atTabRoot = destination == null || destination.route == routes.getValue(currentTab).second
  // A message is about the screen it was shown on: it leaves with it.
  LaunchedEffect(destination?.route, dismissSnackbarOnNavigation) {
    if (dismissSnackbarOnNavigation) snackbarHostState.currentSnackbarData?.dismiss()
  }
  Scaffold(
      modifier = modifier.testTag(if (useAppTags) C.Tag.app_shell else AssociationTags.SHELL),
      topBar = topBar,
      contentWindowInsets = contentWindowInsets,
      snackbarHost = {
        SnackbarHost(snackbarHostState) { data -> AssociationSnackbar(data.visuals.message) }
      },
      bottomBar = {
        if (showBottomBar ?: atTabRoot) {
          AssociationBottomBar(currentTab, useAppTags) { tab ->
            navController.navigateToTab(routes.getValue(tab).first)
          }
        }
      },
  ) { padding ->
    NavHost(
        navController = navController,
        startDestination = routes.getValue(AssociationTab.EVENTS).first,
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        builder = tabGraphs,
    )
  }
}

/**
 * Shows [route] with the screen it was on, like the students' tabs (#42): the other tabs' stacks
 * are saved, Back from another tab's root returns to Events, and selecting the current tab again
 * does not stack a second copy of it.
 */
private fun NavHostController.navigateToTab(route: String) {
  navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
  }
}

/** Figma's dark Snackbar: 20 dp from each side and 12 dp above the bottom bar. */
@Composable
private fun AssociationSnackbar(message: String) {
  Box(
      Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
          .fillMaxWidth()
          .heightIn(min = 60.dp)
          .background(Ink, RoundedCornerShape(14.dp))
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .testTag(AssociationTags.SNACKBAR),
      contentAlignment = Alignment.CenterStart,
  ) {
    Text(
        message,
        style =
            TextStyle(
                    fontFamily = PlusJakartaSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 17.6.sp,
                )
                .exact(),
        color = Bg,
    )
  }
}

/** Figma's association bottom bar, styled like the students' one ("09 · App shell"). */
@Composable
private fun AssociationBottomBar(
    currentTab: AssociationTab,
    useAppTags: Boolean,
    onTabSelected: (AssociationTab) -> Unit,
) {
  Column {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    NavigationBar(
        modifier =
            Modifier.testTag(if (useAppTags) C.Tag.bottom_nav else AssociationTags.BOTTOM_BAR)
    ) {
      AssociationTab.entries.forEach { tab ->
        val selected = tab == currentTab
        NavigationBarItem(
            selected = selected,
            onClick = { onTabSelected(tab) },
            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
            label = {
              Text(
                  stringResource(tab.label),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
              )
            },
            colors =
                NavigationBarItemDefaults.colors(
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = Ink3,
                    unselectedTextColor = Ink3,
                ),
            modifier =
                Modifier.testTag(
                    if (!useAppTags) tab.navItemTag
                    else
                        when (tab) {
                          AssociationTab.EVENTS -> C.Tag.nav_item_events
                          AssociationTab.MAP -> C.Tag.nav_item_map
                          AssociationTab.ORGANIZATION -> C.Tag.nav_item_organization
                        }
                ),
        )
      }
    }
  }
}
