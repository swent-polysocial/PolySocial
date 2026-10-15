// Contributors: Claude (app shell with bottom navigation and app bar, #41; theme title style after
// review; bottom bar matched to the Figma; loading state, #43; per-tab back stacks, #42; Events
// list, #105; "+" button and Create Event navigation from the Events and Map tabs, #110; app bar
// actions, #84; event detail route, #112).
// Contributors: OpenAI Codex (map route and detail placeholder, #50).
package com.polysocial.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
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
import com.polysocial.ui.event.detail.EventDetailRoute
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.list.EventsRoute
import com.polysocial.ui.map.MapRoute
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink3

/**
 * Tabs that open Create Event from a "+" button: over the Events list, and among the map's overlays
 * (the map screen places it next to its own buttons).
 */
private val createEventTabs = setOf(Tab.EVENTS, Tab.MAP)

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
 * Root of the signed-in app: an app bar with the current tab's title and, at the tab's root, its
 * action (see [TabAction]), the current tab's screen, and the bottom navigation bar. The Events tab
 * shows the upcoming events and the Map tab the event map, both with the "+" button that opens
 * Create Event inside the tab, full screen; the other tabs show the shared loading state, then a
 * placeholder until their feature is built. The map uses floating overlays instead of the app bar.
 *
 * Each tab's graph also holds the event detail ([Tab.eventDetailRoute]), so an event opened from a
 * tab keeps that tab selected and Back returns to it. The detail has its own header and action bar
 * (Figma "Event detail"), so the app bar and the bottom bar are hidden there.
 *
 * @param eventsContent the Events tab's root screen; it opens an event's detail with the callback
 *   it gets. Tests replace it, because the real one needs Hilt.
 * @param mapContent the Map tab root, given what opens an event's details and what the "+" opens.
 *   Tests replace its Hilt and native renderer boundary.
 * @param createEventContent the Create Event screen. Tests replace it, because the real one needs
 *   Hilt.
 * @param eventDetailContent the event detail screen, given the Back action. Tests replace it too.
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    eventsContent: @Composable (onEventClick: (String) -> Unit) -> Unit = { onEventClick ->
      EventsRoute(onEventClick = onEventClick)
    },
    mapContent: @Composable (onViewDetails: (String) -> Unit, onCreateEvent: () -> Unit) -> Unit =
        { onDetails, onCreateEvent ->
          MapRoute(onDetails, onCreateEvent = onCreateEvent)
        },
    createEventContent: @Composable (CreateEventNavigation) -> Unit = { navigation ->
      CreateEventScreen(
          onClose = navigation.onClose,
          onViewEvent = navigation.onViewEvent,
          onBackToMap = navigation.onBackToMap,
      )
    },
    eventDetailContent: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
      EventDetailRoute(onBack = onBack)
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
  // The app bar action (TabAction, set per tab in Tab.action) shows only on the current tab's root
  // screen: it is hidden on the screens opened from there, including the one the action opens.
  val action = currentTab.action.takeIf { destination?.route == currentTab.rootRoute }

  val onEventDetail = destination?.route == currentTab.eventDetailRoute

  Scaffold(
      modifier = modifier.testTag(C.Tag.app_shell),
      // The detail draws its own header behind the status bar and keeps its action bar above the
      // navigation bar, so it gets no system insets from the Scaffold.
      contentWindowInsets =
          if (onEventDetail) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
      topBar = {
        if (showBars && !onEventDetail && destination?.route != Tab.MAP.rootRoute)
            AppBar(
                title = stringResource(currentTab.label),
                action = action,
                // A double tap must not open the screen twice.
                onAction = {
                  navController.navigate(currentTab.routeOf(it)) { launchSingleTop = true }
                },
            )
      },
      bottomBar = {
        if (showBars && !onEventDetail) {
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
          composable(tab.rootRoute) { entry ->
            // dropUnlessResumed: a second quick tap doesn't open a second Create Event.
            val openCreateEvent = dropUnlessResumed { navController.navigate(tab.createEventRoute) }
            Box(Modifier.fillMaxSize()) {
              when (tab) {
                Tab.MAP ->
                    mapContent(
                        { id -> navController.navigate(mapDetailRoute(id)) },
                        openCreateEvent,
                    )
                Tab.EVENTS -> {
                  eventsContent { eventId ->
                    if (entry.isResumed) navController.navigate(tab.eventDetailRoute(eventId))
                  }
                  // Figma: 16 dp from the screen edge and 20 dp above the bottom bar.
                  CreateEventButton(
                      onClick = openCreateEvent,
                      modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp, 20.dp),
                  )
                }
                else -> TabRootScreen(tab)
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
              // onViewEvent takes the event id, so it checks the lifecycle like dropUnlessResumed.
              val lifecycle = LocalLifecycleOwner.current.lifecycle
              createEventContent(
                  CreateEventNavigation(
                      onClose = dropUnlessResumed { navController.popBackStack() },
                      onViewEvent = { eventId ->
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                          navController.openCreatedEvent(tab, eventId)
                        }
                      },
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
          tab.action?.let { action ->
            composable(tab.routeOf(action)) { PlaceholderActionScreen(action) }
          }
          composable(
              tab.eventDetailRoute,
              arguments =
                  listOf(
                      navArgument(EventDetailViewModel.EVENT_ID_ARG) { type = NavType.StringType }
                  ),
          ) { entry ->
            eventDetailContent { if (entry.isResumed) navController.popBackStack() }
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
private fun NavHostController.openCreatedEvent(tab: Tab, eventId: String) {
  // TODO(#112): open the new event's detail screen instead of the placeholder.
  val route = if (tab == Tab.MAP) mapDetailRoute(eventId) else tab.detailRoute
  navigate(route) { popUpTo(tab.createEventRoute) { inclusive = true } }
}

/** The Map tab's placeholder detail for [eventId] (its route takes the id, unlike the others). */
private fun mapDetailRoute(eventId: String) = "${Tab.MAP.detailRoute}/${Uri.encode(eventId)}"

/**
 * True while this destination is the one on screen. A second tap during the transition, when the
 * entry is no longer resumed, must be ignored: a second Back would pop the tab's root too and leave
 * a blank screen, and a second tap on a card would open the detail twice.
 */
private val NavBackStackEntry.isResumed: Boolean
  get() = lifecycle.currentState == Lifecycle.State.RESUMED

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
private fun AppBar(title: String, action: TabAction?, onAction: (TabAction) -> Unit) {
  // Figma: the title starts 20 dp from the left; the action's 22 dp icon ends 31 dp from the right,
  // centred on the title, inside a 48 dp touch target. The bar keeps that height with or without an
  // action, so the content below doesn't move.
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .windowInsetsPadding(WindowInsets.statusBars)
              .padding(start = 20.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
              .heightIn(min = 48.dp)
  ) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.align(Alignment.CenterStart).testTag(C.Tag.app_bar_title),
    )
    if (action != null) {
      IconButton(
          onClick = { onAction(action) },
          modifier = Modifier.align(Alignment.CenterEnd).testTag(action.tag),
      ) {
        Icon(
            painter = painterResource(action.icon),
            contentDescription = stringResource(action.label),
            tint = Ink,
        )
      }
    }
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
