// Contributors: Claude (app shell and tab test tags, #41; loading state tag, #43; Create Event
// tags and its "+" button, #46).
package com.polysocial.resources

// Like R, but C
object C {
  object Tag {
    const val greeting_robo = "second_screen_greeting"

    const val second_screen_container = "second_screen_container"

    const val app_shell = "app_shell"
    const val app_bar_title = "app_bar_title"
    const val bottom_nav = "bottom_nav"
    const val loading_state = "loading_state"

    const val nav_item_events = "nav_item_events"
    const val nav_item_map = "nav_item_map"
    const val nav_item_chats = "nav_item_chats"
    const val nav_item_profile = "nav_item_profile"

    const val screen_events = "screen_events"
    const val screen_map = "screen_map"
    const val screen_chats = "screen_chats"
    const val screen_profile = "screen_profile"

    const val create_event_button = "create_event_button"
    const val create_event_screen = "create_event_screen"
    const val create_event_close = "create_event_close"
    const val create_event_title = "create_event_title"
    const val create_event_description = "create_event_description"
    const val create_event_date = "create_event_date"
    const val create_event_start = "create_event_start"
    const val create_event_end = "create_event_end"
    const val create_event_end_next_day = "create_event_end_next_day"
    const val create_event_location = "create_event_location"
    const val create_event_capacity = "create_event_capacity"
    const val create_event_private = "create_event_private"
    const val create_event_public = "create_event_public"
    const val create_event_submit = "create_event_submit"
    const val create_event_failure = "create_event_failure"
    const val create_event_dialog_confirm = "create_event_dialog_confirm"
    const val create_event_dialog_dismiss = "create_event_dialog_dismiss"
    const val event_created_screen = "event_created_screen"
    const val event_created_view_event = "event_created_view_event"
    const val event_created_back_to_map = "event_created_back_to_map"

    /** The chip for [category], e.g. `create_event_category_STUDY`. */
    fun createEventCategory(category: String) = "create_event_category_$category"

    /** The inline error with this name, e.g. `create_event_error_MISSING_TITLE`. */
    fun createEventError(error: String) = "create_event_error_$error"
  }
}
