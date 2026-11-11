// Contributors: Claude (Opus 5.5, Anthropic) wrote it after tracing the random sign-up test
// failures to the keyboard opening late, #314.
package com.polysocial

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Up to how long the keyboard may take to close; it usually closes in well under a second. */
private const val KEYBOARD_TIMEOUT_MS = 10_000L

/**
 * Closes the soft keyboard of the activity's window and waits until it has left the screen.
 *
 * The keyboard belongs to another process (the input method, such as Gboard), which answers a text
 * field's focus late: when typing returns, it may not have started opening yet, or still be sliding
 * in. Compose's idling doesn't wait for it, so on a screen with `imePadding()` the test can scroll
 * to a button, then the keyboard shrinks the screen and the tap lands where the button was, or the
 * button leaves the screen right after. Call this after typing, before tapping or checking what is
 * below the fields (#314). Hiding is the state every device reaches; whether and how far the
 * keyboard opens depends on the device (a hardware keyboard, a password field).
 *
 * Only for fields in the activity's own window, not in a dialog or a bottom sheet.
 */
fun AndroidComposeTestRule<*, *>.closeKeyboard() {
  val window = activity.window
  val ime = WindowInsetsCompat.Type.ime()
  // Also cancels a keyboard that was asked for but hasn't appeared yet.
  runOnUiThread { WindowCompat.getInsetsController(window, window.decorView).hide(ime) }
  waitUntil("the keyboard is closed", KEYBOARD_TIMEOUT_MS) {
    runOnUiThread {
      val insets = ViewCompat.getRootWindowInsets(window.decorView)
      insets == null || (!insets.isVisible(ime) && insets.getInsets(ime).bottom == 0)
    }
  }
  // Lets the screen lay out again without the keyboard.
  waitForIdle()
}
