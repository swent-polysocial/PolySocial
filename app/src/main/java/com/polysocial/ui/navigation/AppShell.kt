// Contributors: Claude (app shell with bottom navigation and app bar, #41; theme title style after
// review; bottom bar matched to the Figma; loading state, #43; per-tab back stacks, #42).
package com.polysocial.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.polysocial.resources.C
import com.polysocial.ui.theme.Ink3

/**
 * Root of the signed-in app: an app bar with the current tab's title, the current tab's screen, and
 * the bottom navigation bar. Each tab shows the shared loading state, then a placeholder until its
 * feature is built.
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
  val backStackEntry by navController.currentBackStackEntryAsState()
  val destination = backStackEntry?.destination
  val currentTab =
      Tab.entries.firstOrNull { tab ->
        destination?.hierarchy?.any { it.route == tab.route } == true
      } ?: Tab.EVENTS

  Scaffold(
      modifier = modifier.testTag(C.Tag.app_shell),
      topBar = { AppBar(title = stringResource(currentTab.label)) },
      bottomBar = {
        BottomNavBar(currentTab = currentTab, onTabSelected = { navController.navigateToTab(it) })
      },
  ) { padding ->
    NavHost(
        navController = navController,
        startDestination = Tab.EVENTS.route,
        modifier = Modifier.padding(padding),
    ) {
      // One nested graph per tab, so each tab keeps its own back stack.
      Tab.entries.forEach { tab ->
        navigation(startDestination = tab.rootRoute, route = tab.route) {
          composable(tab.rootRoute) { TabRootScreen(tab) }
          composable(tab.detailRoute) { PlaceholderDetailScreen(tab) }
        }
      }
    }
  }
}

/**
 * Shows [tab] with the screen it was on. The other tabs' stacks are saved and removed, so the back
 * stack holds the Events tab (the start destination) and at most one other tab: back from another
 * tab's root goes to Events, and back from the Events root exits. [tab]'s saved stack (or its root)
 * is restored, and selecting the current tab again does not stack a second copy of it.
 */
private fun NavHostController.navigateToTab(tab: Tab) {
  navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
  }
}

@Composable
private fun AppBar(title: String) {
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .windowInsetsPadding(WindowInsets.statusBars)
              .padding(horizontal = 20.dp, vertical = 16.dp)
  ) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.testTag(C.Tag.app_bar_title),
    )
  }
}

@Composable
private fun BottomNavBar(currentTab: Tab, onTabSelected: (Tab) -> Unit) {
  // Figma "09 · App shell": a thin border above the bar, dark labels (bold when selected) and Ink3
  // for unselected tabs. The light-red pill and the red selected icon come from the theme.
  Column {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    NavigationBar(modifier = Modifier.testTag(C.Tag.bottom_nav)) {
      Tab.entries.forEach { tab ->
        val selected = tab == currentTab
        NavigationBarItem(
            selected = selected,
            onClick = { onTabSelected(tab) },
            icon = { Icon(painter = painterResource(tab.icon), contentDescription = null) },
            label = {
              Text(
                  text = stringResource(tab.label),
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
            modifier = Modifier.testTag(tab.navItemTag),
        )
      }
    }
  }
}
