// Contributors: Claude (tab definitions for the bottom navigation, #41; loading messages, #43;
// per-tab routes, #42; Create Event and event detail routes, #46).
package com.polysocial.ui.navigation

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.polysocial.R
import com.polysocial.resources.C

/**
 * The four top-level tabs of the app, in the order they appear in the bottom navigation bar.
 *
 * @property route Navigation route of the tab's own graph, which holds its back stack.
 * @property label Tab name, shown in the bottom bar and as the app bar title.
 * @property icon Icon shown in the bottom bar and on the tab's placeholder screen.
 * @property loadingMessage Message of the shared loading state while the tab's content loads.
 * @property navItemTag Test tag of the tab's bottom bar item.
 * @property screenTag Test tag of the tab's root screen.
 */
enum class Tab(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @StringRes val loadingMessage: Int,
    val navItemTag: String,
    val screenTag: String,
) {
  EVENTS(
      "events",
      R.string.tab_events,
      R.drawable.ic_tab_events,
      R.string.loading_events,
      C.Tag.nav_item_events,
      C.Tag.screen_events,
  ),
  MAP(
      "map",
      R.string.tab_map,
      R.drawable.ic_tab_map,
      R.string.loading_map,
      C.Tag.nav_item_map,
      C.Tag.screen_map,
  ),
  CHATS(
      "chats",
      R.string.tab_chats,
      R.drawable.ic_tab_chats,
      R.string.loading_chats,
      C.Tag.nav_item_chats,
      C.Tag.screen_chats,
  ),
  PROFILE(
      "profile",
      R.string.tab_profile,
      R.drawable.ic_tab_profile,
      R.string.loading_profile,
      C.Tag.nav_item_profile,
      C.Tag.screen_profile,
  );

  /** Route of the tab's root screen, the start destination of its graph. */
  val rootRoute: String
    get() = "$route/root"

  /**
   * Route of the tab's placeholder detail screen, one level below its root. Only tests open it for
   * now. Real screens (e.g. an event's detail from the Map preview card, #50) replace it.
   */
  val detailRoute: String
    get() = "$route/detail"

  /** Test tag of the tab's placeholder detail screen. */
  val detailTag: String
    get() = "detail_$route"

  /** Whether the tab's root shows the "+" button that opens Create Event (Events and Map). */
  val canCreateEvent: Boolean
    get() = this == EVENTS || this == MAP

  /** Route of the Create Event screen, in the back stack of the tab whose "+" opened it. */
  val createEventRoute: String
    get() = "$route/create"

  /** Route pattern of an event's detail screen in this tab's back stack. */
  val eventDetailRoute: String
    get() = "$route/event/{eventId}"

  /** Route of the detail screen of the event [eventId] in this tab's back stack. */
  fun eventDetailRoute(eventId: String): String = "$route/event/${Uri.encode(eventId)}"
}
