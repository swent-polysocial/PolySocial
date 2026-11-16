// Contributors: Claude (Opus 5.5, Anthropic) wrote it after tracing the random sign-up test
// failures to the keyboard opening late, #314; Claude (Opus 5.5, Anthropic) made it close the
// keyboard of the window that has focus (dialogs, bottom sheets) after Compose sent its request,
// #427.
package com.polysocial

import android.os.Build
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Up to how long the keyboard may take to close; it usually closes in well under a second. */
private const val KEYBOARD_TIMEOUT_MS = 10_000L

/**
 * Closes the soft keyboard and waits until it has left the screen.
 *
 * The keyboard belongs to another process (the input method, such as Gboard), which answers a text
 * field's focus late: when typing returns, it may not have started opening yet, or still be sliding
 * in. Compose's idling doesn't wait for it, so on a screen with `imePadding()` the test can scroll
 * to a button, then the keyboard shrinks the screen and the tap lands where the button was, or the
 * button leaves the screen right after. Call this after typing, before tapping or checking what is
 * below the fields (#314). It waits for the keyboard to be hidden, the state every device reaches,
 * not for a fixed time or for the keyboard to open: whether and how far it opens depends on the
 * device (a hardware keyboard, a password field).
 *
 * It closes the keyboard of the app's window that has focus, the one it types into: the activity's,
 * or, on Android 10+, a dialog's or bottom sheet's above it (the date picker, Delete account).
 * Don't call it while a menu is open: the menu's popup has focus then, so it would close and wait
 * on the popup's window instead of the field's.
 */
fun AndroidComposeTestRule<*, *>.closeKeyboard() {
  // A field Compose has just focused asks for the keyboard on the next frame. Letting it ask first
  // means the system gets the hide after it, instead of a hide the request then undoes.
  waitForIdle()
  val activityWindow = activity.window.decorView
  val window = runOnUiThread { focusedWindow() ?: activityWindow }
  val ime = WindowInsetsCompat.Type.ime()
  // Cancels a keyboard Compose asked for, which goes through the window's insets controller;
  // one asked for only through InputMethodManager can still flash after this (seen on API 34).
  runOnUiThread { SoftwareKeyboardControllerCompat(window).hide() }
  waitUntil("the keyboard is closed", KEYBOARD_TIMEOUT_MS) {
    runOnUiThread {
      // Null once the window is gone (a dialog closed), and its keyboard with it.
      val insets = ViewCompat.getRootWindowInsets(window)
      insets == null || (!insets.isVisible(ime) && insets.getInsets(ime).bottom == 0)
    }
  }
  // Lets the screen lay out again without the keyboard.
  waitForIdle()
}

/**
 * The root view of the app's window that has focus, or null if none has (or, before Android 10,
 * which can't list the app's windows, always).
 */
private fun focusedWindow(): View? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
    else null
