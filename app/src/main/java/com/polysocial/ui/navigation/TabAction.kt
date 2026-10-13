// Contributors: Claude (app bar actions from the Figma Events, Chats and Profile frames, #84).
package com.polysocial.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.polysocial.R
import com.polysocial.resources.C

/**
 * The action on the right of a tab's app bar, as in Figma. Each one opens a screen inside its tab,
 * a placeholder until its feature is built.
 *
 * @property path Last segment of the action's route, below its tab's route (see [Tab.routeOf]).
 * @property icon Icon shown in the app bar.
 * @property label What the action opens: the icon's content description and the placeholder's name.
 * @property tag Test tag of the action's app bar button.
 */
enum class TabAction(
    val path: String,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val tag: String,
) {
  NOTIFICATIONS(
      "notifications",
      R.drawable.ic_bell,
      R.string.action_notifications,
      C.Tag.app_bar_action_notifications,
  ),
  SEARCH_CHATS(
      "search",
      R.drawable.ic_search,
      R.string.action_search_chats,
      C.Tag.app_bar_action_search_chats,
  ),
  EDIT_PROFILE(
      "edit",
      R.drawable.ic_edit,
      R.string.action_edit_profile,
      C.Tag.app_bar_action_edit_profile,
  );

  /** Test tag of the action's placeholder screen. */
  val screenTag: String
    get() = "action_screen_$path"
}
