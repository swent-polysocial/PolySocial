// Contributors: OpenAI Codex (preserved navigation, logout and pending edits on rebase, #140).
// Contributors: OpenAI Codex (association tabs, Organization and account-specific stack cleanup,
// #140).
// Contributors: OpenAI Codex (Profile logout route and clear tab stacks, #109).
// Contributors: Claude (app shell with bottom navigation and app bar, #41; theme title style after
// review; bottom bar matched to the Figma; loading state, #43; per-tab back stacks, #42; Events
// list, #105; "+" button and Create Event navigation from the Events and Map tabs, #110; app bar
// actions, #84; event detail route, #112; "View event" opens the event detail, #211; the
// association's Events tab, #132; Edit event route, #131; closing it during its
// enter transition, #131 review; Edit and Cancel event from the detail, #131; the map's Details
// open the event detail, #112, after the M1 coach review).
// Contributors: OpenAI Codex (map route and detail placeholder, #50).
// Contributors: OpenAI Codex (Profile privacy navigation and full-screen notices, #103).
// Contributors: Claude (Opus 5.5, Anthropic) left the app through logout's reset after account
// deletion, #207; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (Opus 5.5, Anthropic) opened Find a group from the detail, #233.
// Contributors: Claude (Opus 5.5, Anthropic) showed an association's own events on its Map tab,
// #133; its header on the Events and Map tabs, #322, scoped to the Events graph after review.
// Contributors: OpenAI Codex (wired the group roster below the student event detail, #234).
package com.polysocial.ui.navigation

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.polysocial.R
import com.polysocial.model.user.AccountType
import com.polysocial.resources.C
import com.polysocial.ui.association.AssociationShell
import com.polysocial.ui.association.AssociationTab
import com.polysocial.ui.association.AssociationTabHeader
import com.polysocial.ui.association.OrganizationRoute
import com.polysocial.ui.event.create.CreateEventButton
import com.polysocial.ui.event.create.CreateEventScreen
import com.polysocial.ui.event.create.EditEventScreen
import com.polysocial.ui.event.detail.EventDetailRoute
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.list.EventsTabRoute
import com.polysocial.ui.group.FindGroupDone
import com.polysocial.ui.group.FindGroupRoute
import com.polysocial.ui.group.YourGroupRoute
import com.polysocial.ui.map.MapRoute
import com.polysocial.ui.map.mapViewModel
import com.polysocial.ui.profile.FullPrivacyNoticeRoute
import com.polysocial.ui.profile.PrivacyRoute
import com.polysocial.ui.profile.ProfileRoute
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink3
import kotlinx.coroutines.launch

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
 * Where the event detail leaves to: [onBack] to the screen it was opened from, [onEdit] to Edit
 * event, [onCancelled] back to that screen once the event is cancelled, which then says so, and
 * [onFindGroup] to Find a group (#233) and [onOpenGroup] to the group roster (#234).
 */
data class EventDetailNavigation(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onCancelled: () -> Unit,
    val onFindGroup: () -> Unit = {},
    val onOpenGroup: () -> Unit = {},
)

/**
 * Approved associations use Events, Map and Organization; students retain their four tabs.
 * Organization reuses the Profile graph for its own identity, privacy and cache-first logout. The
 * association's Map shows its own upcoming events (#133) under the app bar, which the students' map
 * leaves out. On the association's Events and Map tabs, the app bar shows its header (initials,
 * name with the Verified tick, and the tab's title, #322) instead of the title alone.
 *
 * Root of the signed-in app: an app bar with the current tab's title and, at the tab's root, its
 * action (see [TabAction]), the current tab's screen, and the bottom navigation bar. The Events tab
 * shows the upcoming events (a verified association's own events instead, #132) and the Map tab the
 * event map, both with the "+" button that opens Create Event inside the tab, full screen; the
 * Profile provides logout and Chats shows a loading state, then a placeholder until their feature
 * is built. The map uses floating overlays instead of the app bar.
 *
 * Each tab's graph also holds the event detail ([Tab.eventDetailRoute]), so an event opened from a
 * tab keeps that tab selected and Back returns to it. The detail has its own header and action bar
 * (Figma "Event detail"), so the app bar and the bottom bar are hidden there. Edit event
 * ([Tab.editEventRoute], #131) opens one level below the detail, full screen like Create Event.
 *
 * @param eventsContent the Events tab's root screen; it opens an event's detail with the callback
 *   it gets. Tests replace it, because the real one needs Hilt.
 * @param mapContent the Map tab root, given what opens an event's details and what the "+" opens.
 *   Tests replace its Hilt and native renderer boundary.
 * @param createEventContent the Create Event screen. Tests replace it, because the real one needs
 *   Hilt.
 * @param eventDetailContent the event detail screen, given where it leaves to. Tests replace it
 *   too.
 * @param editEventContent the Edit event screen, given what leaves it (back to the detail). Tests
 *   replace it, because the real one needs Hilt.
 * @param associationHeader the verified association's header on its Events and Map tabs, given the
 *   tab's title (#322). Its ViewModels belong to the Events graph, so logout clears them. Tests
 *   replace it, because the real one needs Hilt.
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    accountType: AccountType = AccountType.STUDENT,
    navController: NavHostController = rememberNavController(),
    eventsContent: @Composable (onEventClick: (String) -> Unit) -> Unit = { onEventClick ->
      EventsTabRoute(onEventClick = onEventClick)
    },
    mapContent: @Composable (onViewDetails: (String) -> Unit, onCreateEvent: () -> Unit) -> Unit =
        { onDetails, onCreateEvent ->
          MapRoute(onDetails, onCreateEvent = onCreateEvent, viewModel = mapViewModel(accountType))
        },
    createEventContent: @Composable (CreateEventNavigation) -> Unit = { navigation ->
      CreateEventScreen(
          onClose = navigation.onClose,
          onViewEvent = navigation.onViewEvent,
          onBackToMap = navigation.onBackToMap,
      )
    },
    eventDetailContent: @Composable (EventDetailNavigation) -> Unit = { navigation ->
      EventDetailRoute(
          onBack = navigation.onBack,
          onEdit = navigation.onEdit,
          onCancelled = navigation.onCancelled,
          onFindGroup = navigation.onFindGroup,
          onOpenGroup = navigation.onOpenGroup,
      )
    },
    editEventContent: @Composable (onClose: () -> Unit) -> Unit = { onClose ->
      EditEventScreen(onClose = onClose)
    },
    findGroupContent: @Composable (onBack: () -> Unit, onDone: (FindGroupDone) -> Unit) -> Unit =
        { onBack, onDone ->
          FindGroupRoute(onBack = onBack, onDone = onDone)
        },
    associationHeader: @Composable (title: Int) -> Unit = { title -> AssociationTabHeader(title) },
    onLoggedOut: () -> Unit = {},
    onAccountDeleted: () -> Unit = onLoggedOut,
    organizationContent: @Composable (() -> Unit, () -> Unit) -> Unit = { done, privacy ->
      OrganizationRoute(done, privacy)
    },
    profileContent: @Composable (() -> Unit) -> Unit = { done ->
      ProfileRoute(
          onLoggedOut = done,
          onOpenPrivacy = dropUnlessResumed { navController.navigate(Tab.PROFILE.privacyRoute) },
      )
    },
    yourGroupContent: @Composable (onBack: () -> Unit, onLeft: () -> Unit) -> Unit =
        { onBack, onLeft ->
          YourGroupRoute(onBack, onLeft)
        },
) {
  val association = accountType == AccountType.ASSOCIATION
  val tabs = if (association) listOf(Tab.EVENTS, Tab.MAP, Tab.PROFILE) else Tab.entries
  val backStackEntry by navController.currentBackStackEntryAsState()
  val destination = backStackEntry?.destination
  val currentTab =
      tabs.firstOrNull { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }
          ?: Tab.EVENTS
  // Figma shows Create Event full screen, without the app bar and the bottom bar.
  val privacyScreen =
      destination?.route == Tab.PROFILE.privacyRoute ||
          destination?.route == Tab.PROFILE.privacyNoticeRoute
  val showBars =
      destination?.route != currentTab.createEventRoute &&
          destination?.route != currentTab.editEventRoute &&
          destination?.route != currentTab.findGroupRoute &&
          destination?.route != currentTab.yourGroupRoute &&
          !privacyScreen
  // The app bar action (TabAction, set per tab in Tab.action) shows only on the current tab's root
  // screen: it is hidden on the screens opened from there, including the one the action opens.
  val action =
      currentTab.action.takeIf {
        destination?.route == currentTab.rootRoute && !(association && currentTab == Tab.PROFILE)
      }

  val onEventDetail = destination?.route == currentTab.eventDetailRoute
  // Shown on the screen the detail returns to once its event is cancelled (Figma "Cancel event ·
  // cancelled").
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val eventCancelled = stringResource(R.string.event_cancelled)
  val groupJoined = stringResource(R.string.find_group_joined)
  val groupStarted = stringResource(R.string.find_group_started)

  val leaveApp = { then: () -> Unit ->
    tabs.forEach { navController.clearBackStack(it.route) }
    navController.popBackStack(Tab.EVENTS.route, inclusive = true)
    then()
  }

  val tabGraphs: NavGraphBuilder.() -> Unit = {
    // One nested graph per tab, so each tab keeps its own back stack.
    tabs.forEach { tab ->
      navigation(startDestination = tab.rootRoute, route = tab.route) {
        composable(tab.rootRoute) { entry ->
          // dropUnlessResumed: a second quick tap doesn't open a second Create Event.
          val openCreateEvent = dropUnlessResumed { navController.navigate(tab.createEventRoute) }
          Box(Modifier.fillMaxSize()) {
            when (tab) {
              Tab.MAP ->
                  mapContent(
                      { id ->
                        if (entry.isResumed) navController.navigate(tab.eventDetailRoute(id))
                      },
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
              Tab.PROFILE -> {
                val loggedOut = { leaveApp(onLoggedOut) }
                if (association)
                    organizationContent(
                        loggedOut,
                        dropUnlessResumed {
                          navController.navigate(tab.privacyRoute) { launchSingleTop = true }
                        },
                    )
                else profileContent(loggedOut)
              }
              else -> TabRootScreen(tab)
            }
          }
        }
        composable(tab.detailRoute) { PlaceholderDetailScreen(tab) }
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
        if (tab == Tab.PROFILE) {
          composable(tab.privacyRoute) {
            PrivacyRoute(
                onBack = { navController.popBackStack() },
                onFullNotice = { navController.navigate(tab.privacyNoticeRoute) },
                onSessionEnded = { deleted ->
                  leaveApp(if (deleted) onAccountDeleted else onLoggedOut)
                },
            )
          }
          composable(tab.privacyNoticeRoute) {
            FullPrivacyNoticeRoute(onBack = { navController.popBackStack() })
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
          val id = entry.arguments?.getString(EventDetailViewModel.EVENT_ID_ARG)
          eventDetailContent(
              EventDetailNavigation(
                  onBack = { if (entry.isResumed) navController.popBackStack() },
                  onEdit = {
                    if (entry.isResumed && id != null) {
                      navController.navigate(tab.editEventRoute(id))
                    }
                  },
                  onFindGroup = {
                    if (entry.isResumed && id != null) {
                      navController.navigate(tab.findGroupRoute(id))
                    }
                  },
                  onOpenGroup = {
                    if (!association && entry.isResumed && id != null) {
                      navController.navigate(tab.yourGroupRoute(id))
                    }
                  },
                  // Not isResumed: the event may be deleted while a transition runs.
                  onCancelled = {
                    if (navController.currentBackStackEntry == entry) {
                      navController.popBackStack()
                      snackbarHostState.currentSnackbarData?.dismiss()
                      scope.launch { snackbarHostState.showSnackbar(eventCancelled) }
                    }
                  },
              )
          )
        }
        composable(
            tab.editEventRoute,
            arguments =
                listOf(
                    navArgument(EventDetailViewModel.EVENT_ID_ARG) { type = NavType.StringType }
                ),
        ) { entry ->
          // Not isResumed: Edit event can close itself during its enter transition (an event
          // that can't be read), and must not stay open on a blank screen then.
          editEventContent {
            if (navController.currentBackStackEntry == entry) navController.popBackStack()
          }
        }
        if (!association) {
          composable(
              tab.yourGroupRoute,
              arguments =
                  listOf(
                      navArgument(EventDetailViewModel.EVENT_ID_ARG) { type = NavType.StringType }
                  ),
          ) { entry ->
            yourGroupContent(
                { if (entry.isResumed) navController.popBackStack() },
                { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
            )
          }
        }
        composable(
            tab.findGroupRoute,
            arguments =
                listOf(
                    navArgument(EventDetailViewModel.EVENT_ID_ARG) { type = NavType.StringType }
                ),
        ) { entry ->
          findGroupContent(
              { if (entry.isResumed) navController.popBackStack() },
              { done ->
                // Back on the detail, which then shows the group (#233), say what happened.
                if (navController.currentBackStackEntry == entry) {
                  navController.popBackStack()
                  snackbarHostState.currentSnackbarData?.dismiss()
                  val text = if (done == FindGroupDone.STARTED) groupStarted else groupJoined
                  scope.launch { snackbarHostState.showSnackbar(text) }
                }
              },
          )
        }
      }
    }
  }
  val topBar: @Composable () -> Unit = {
    // The students' map draws its search where the app bar would be; the association's keeps the
    // bar for its header, like its Events tab.
    if (showBars && !onEventDetail && (association || destination?.route != Tab.MAP.rootRoute)) {
      val label = currentTab.presentation(association).label
      val withHeader =
          association &&
              (destination?.route == Tab.EVENTS.rootRoute ||
                  destination?.route == Tab.MAP.rootRoute)
      AppBar(
          title = stringResource(label),
          action = action,
          onAction = { navController.navigate(currentTab.routeOf(it)) { launchSingleTop = true } },
          header =
              if (withHeader)
                  ({
                    // The top bar is outside the NavHost, where ViewModels would belong to the
                    // Activity and outlive a logout. The Events graph is the start destination, so
                    // it is under the Map tab too, and logout pops it: the next account reads its
                    // own identity.
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides
                            navController.getBackStackEntry(Tab.EVENTS.route)
                    ) {
                      associationHeader(label)
                    }
                  })
              else null,
      )
    }
  }
  if (association) {
    AssociationShell(
        navController = navController,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        routes =
            mapOf(
                AssociationTab.EVENTS to (Tab.EVENTS.route to Tab.EVENTS.rootRoute),
                AssociationTab.MAP to (Tab.MAP.route to Tab.MAP.rootRoute),
                AssociationTab.ORGANIZATION to (Tab.PROFILE.route to Tab.PROFILE.rootRoute),
            ),
        showBottomBar = showBars && !onEventDetail,
        topBar = topBar,
        contentWindowInsets =
            if (onEventDetail) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        useAppTags = true,
        dismissSnackbarOnNavigation = false,
        tabGraphs = tabGraphs,
    )
  } else {
    Scaffold(
        modifier = modifier.testTag(C.Tag.app_shell),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets =
            if (onEventDetail) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = topBar,
        bottomBar = {
          if (showBars && !onEventDetail)
              BottomNavBar(currentTab, tabs, association) { navController.navigateToTab(it) }
        },
    ) { padding ->
      NavHost(
          navController = navController,
          startDestination = Tab.EVENTS.route,
          modifier = Modifier.padding(padding).consumeWindowInsets(padding),
          builder = tabGraphs,
      )
    }
  }
}

/**
 * Replaces Create Event with the new event's detail screen, so Back from it returns to [tab] and
 * never to the confirmation or the filled form.
 */
private fun NavHostController.openCreatedEvent(tab: Tab, eventId: String) {
  navigate(tab.eventDetailRoute(eventId)) { popUpTo(tab.createEventRoute) { inclusive = true } }
}

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

/** The tab's app bar: its [title], or [header] in its place when given, and its [action]. */
@Composable
private fun AppBar(
    title: String,
    action: TabAction?,
    onAction: (TabAction) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
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
    if (header != null) {
      // Leaves room for the action, so a long name is cut before it.
      Box(
          Modifier.align(Alignment.CenterStart).padding(end = if (action != null) 56.dp else 0.dp)
      ) {
        header()
      }
    } else {
      Text(
          text = title,
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.align(Alignment.CenterStart).testTag(C.Tag.app_bar_title),
      )
    }
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
private fun BottomNavBar(
    currentTab: Tab,
    tabs: List<Tab>,
    association: Boolean,
    onTabSelected: (Tab) -> Unit,
) {
  // Figma "09 · App shell": a thin border above the bar, dark labels (bold when selected) and Ink3
  // for unselected tabs. The light-red pill and the red selected icon come from the theme.
  Column {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    NavigationBar(modifier = Modifier.testTag(C.Tag.bottom_nav)) {
      tabs.forEach { tab ->
        val selected = tab == currentTab
        val presentation = tab.presentation(association)
        NavigationBarItem(
            selected = selected,
            onClick = { onTabSelected(tab) },
            icon = {
              Icon(
                  painter = painterResource(presentation.icon),
                  contentDescription = null,
              )
            },
            label = {
              Text(
                  text = stringResource(presentation.label),
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
            modifier = Modifier.testTag(presentation.tag),
        )
      }
    }
  }
}
