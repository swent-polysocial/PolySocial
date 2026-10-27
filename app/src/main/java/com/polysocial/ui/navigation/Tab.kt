// Contributors: Claude (tab definitions for the bottom navigation, #41; loading messages, #43;
// per-tab routes, #42; Create Event route, #110; app bar actions, #84; event detail route, #112;
// placeholder detail doc after "View event" moved to the event detail, #211; Edit event route,
// #131).
// Contributors: OpenAI Codex (Profile privacy and full-notice routes, #103).
package com.polysocial.ui.navigation

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel

/**
 * The four top-level tabs of the app, in the order they appear in the bottom navigation bar.
 *
 * @property route Navigation route of the tab's own graph, which holds its back stack.
 * @property label Tab name, shown in the bottom bar and as the app bar title.
 * @property icon Icon shown in the bottom bar and on the tab's placeholder screen.
 * @property loadingMessage Message of the shared loading state while the tab's content loads.
 * @property navItemTag Test tag of the tab's bottom bar item.
 * @property screenTag Test tag of the tab's root screen.
 * @property action Action on the right of the tab's app bar, or null when Figma has none.
 */
enum class Tab(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @StringRes val loadingMessage: Int,
    val navItemTag: String,
    val screenTag: String,
    val action: TabAction?,
) {
  EVENTS(
      "events",
      R.string.tab_events,
      R.drawable.ic_tab_events,
      R.string.loading_events,
      C.Tag.nav_item_events,
      C.Tag.screen_events,
      TabAction.NOTIFICATIONS,
  ),
  MAP(
      "map",
      R.string.tab_map,
      R.drawable.ic_tab_map,
      R.string.loading_map,
      C.Tag.nav_item_map,
      C.Tag.screen_map,
      null,
  ),
  CHATS(
      "chats",
      R.string.tab_chats,
      R.drawable.ic_tab_chats,
      R.string.loading_chats,
      C.Tag.nav_item_chats,
      C.Tag.screen_chats,
      TabAction.SEARCH_CHATS,
  ),
  PROFILE(
      "profile",
      R.string.tab_profile,
      R.drawable.ic_tab_profile,
      R.string.loading_profile,
      C.Tag.nav_item_profile,
      C.Tag.screen_profile,
      TabAction.EDIT_PROFILE,
  );

  /** Route of the tab's root screen, the start destination of its graph. */
  val rootRoute: String
    get() = "$route/root"

  /**
   * Route of the tab's placeholder detail screen, one level below its root (the Map's takes the
   * event id: `$detailRoute/{eventId}`). The map preview's "Details" still opens the Map's; events
   * otherwise open [eventDetailRoute].
   */
  val detailRoute: String
    get() = "$route/detail"

  /** Route of Create Event inside the tab's graph, so Close and Back return to the tab. */
  val createEventRoute: String
    get() = "$route/create-event"

  /** Route of the screen [action] opens, one level below the tab's root. */
  fun routeOf(action: TabAction): String = "$route/${action.path}"

  /** The read-only privacy summary in the Profile graph (#103). */
  val privacyRoute: String
    get() = "$route/privacy"

  /** The bundled full notice, reached from the Profile privacy summary (#103). */
  val privacyNoticeRoute: String
    get() = "$route/privacy/notice"

  /** Test tag of the tab's placeholder detail screen. */
  val detailTag: String
    get() = "detail_$route"

  /**
   * Route pattern of an event's detail screen inside this tab's graph, so opening an event keeps
   * the tab selected and Back returns to where it was opened (Events list, map, "View event").
   */
  val eventDetailRoute: String
    get() = "$route/event/{${EventDetailViewModel.EVENT_ID_ARG}}"

  /** The route that opens the detail of the event [eventId] inside this tab. */
  fun eventDetailRoute(eventId: String): String = "$route/event/${Uri.encode(eventId)}"

  /**
   * Route pattern of Edit event (#131), one level below the event's detail in this tab's graph, so
   * closing or saving returns to the detail.
   */
  val editEventRoute: String
    get() = "$eventDetailRoute/edit"

  /** The route that opens Edit event for the event [eventId] inside this tab. */
  fun editEventRoute(eventId: String): String = "${eventDetailRoute(eventId)}/edit"
}
