// Contributors: Claude (app shell with bottom navigation and app bar, #41; theme title style after
// review; bottom bar matched to the Figma; loading state, #43; per-tab back stacks, #42; Events
// list, #105; "+" button and Create Event navigation, #110).
// Contributors: OpenAI Codex (map route and detail placeholder, #50).
package com.polysocial.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.event.create.CreateEventButton
import com.polysocial.ui.event.create.CreateEventScreen
import com.polysocial.ui.event.list.EventsRoute
import com.polysocial.ui.map.MapRoute
import com.polysocial.ui.theme.Ink3

/** Tabs whose root shows the "+" button. The Map tab's comes in a follow-up of #110. */
private val createEventTabs = setOf(Tab.EVENTS)

/**
 * Where Create Event leaves to: [onClose] back to the tab it was opened from, [onViewEvent] to the
 * new event, [onBackToMap] to the Map tab.
 */
data class CreateEventNavigation(
    val onClose: () -> Unit,
    val onViewEvent: (eventId: String) -> Unit,
    val onBackToMap: () -> Unit,
)

/**
 * Root of the signed-in app: an app bar with the current tab's title, the current tab's screen, and
 * the bottom navigation bar. The Events tab shows the upcoming events and the "+" button that opens
 * Create Event inside the tab, full screen; the other tabs show the shared loading state, then a
 * placeholder until their feature is built. The map uses floating overlays instead of the app bar.
 *
 * @param eventsContent the Events tab's root screen. Tests replace it, because the real one needs
 *   Hilt.
 * @param mapContent the Map tab root. Tests replace its Hilt and native renderer boundary.
 * @param createEventContent the Create Event screen. Tests replace it, because the real one needs
 *   Hilt.
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    eventsContent: @Composable () -> Unit = { EventsRoute() },
    mapContent: @Composable ((String) -> Unit) -> Unit = { onDetails -> MapRoute(onDetails) },
    createEventContent: @Composable (CreateEventNavigation) -> Unit = { navigation ->
      CreateEventScreen(
          onClose = navigation.onClose,
          // TODO(#111): open the location picker.
          onPickLocation = {},
          onViewEvent = navigation.onViewEvent,
          onBackToMap = navigation.onBackToMap,
      )
    },
) {
  val backStackEntry by navController.currentBackStackEntryAsState()
  val destination = backStackEntry?.destination
  val currentTab =
      Tab.entries.firstOrNull { tab ->
        destination?.hierarchy?.any { it.route == tab.route } == true
      } ?: Tab.EVENTS
  // Figma shows Create Event full screen, without the app bar and the bottom bar.
  val showBars = destination?.route != currentTab.createEventRoute

  Scaffold(
      modifier = modifier.testTag(C.Tag.app_shell),
      topBar = {
        if (showBars && destination?.route != Tab.MAP.rootRoute)
            AppBar(title = stringResource(currentTab.label))
      },
      bottomBar = {
        if (showBars) {
          BottomNavBar(
              currentTab = currentTab,
              onTabSelected = { navController.navigateToTab(it) },
          )
        }
      },
  ) { padding ->
    NavHost(
        navController = navController,
        startDestination = Tab.EVENTS.route,
        // Consumed, so a screen that pads itself for the system bars (Create Event) doesn't add
        // them a second time.
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
    ) {
      // One nested graph per tab, so each tab keeps its own back stack.
      Tab.entries.forEach { tab ->
        navigation(startDestination = tab.rootRoute, route = tab.route) {
          composable(tab.rootRoute) {
            Box(Modifier.fillMaxSize()) {
              when (tab) {
                Tab.MAP ->
                    mapContent { id ->
                      navController.navigate("${tab.detailRoute}/${android.net.Uri.encode(id)}")
                    }
                Tab.EVENTS -> eventsContent()
                else -> TabRootScreen(tab)
              }
              if (tab in createEventTabs) {
                // Figma: 16 dp from the screen edge and 20 dp above the bottom bar.
                // dropUnlessResumed: a second quick tap doesn't open a second Create Event.
                CreateEventButton(
                    onClick = dropUnlessResumed { navController.navigate(tab.createEventRoute) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp, 20.dp),
                )
              }
            }
          }
          if (tab == Tab.MAP) {
            composable(
                "${tab.detailRoute}/{eventId}",
                arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
            ) {
              Column(Modifier.padding(20.dp).testTag(C.Tag.event_detail_placeholder)) {
                Text(
                    stringResource(R.string.event_detail_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.event_detail_placeholder))
                TextButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.testTag(C.Tag.event_detail_back),
                ) {
                  Text(stringResource(R.string.event_detail_back))
                }
              }
            }
          } else {
            composable(tab.detailRoute) { PlaceholderDetailScreen(tab) }
          }
          if (tab in createEventTabs) {
            composable(tab.createEventRoute) {
              // Each action runs once: a second tap while leaving would pop the screen below.
              val viewEvent = dropUnlessResumed { navController.openCreatedEvent(tab) }
              createEventContent(
                  CreateEventNavigation(
                      onClose = dropUnlessResumed { navController.popBackStack() },
                      onViewEvent = { viewEvent() },
                      onBackToMap =
                          dropUnlessResumed {
                            // Leave Create Event first, so the tab doesn't come back on the
                            // confirmation when the student returns to it.
                            navController.popBackStack()
                            navController.navigateToTab(Tab.MAP)
                          },
                  )
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Replaces Create Event with the new event's screen, so Back from it returns to [tab] and never to
 * the confirmation or the filled form.
 */
private fun NavHostController.openCreatedEvent(tab: Tab) {
  // TODO(#112): open the new event's detail screen instead of the placeholder.
  navigate(tab.detailRoute) { popUpTo(tab.createEventRoute) { inclusive = true } }
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
