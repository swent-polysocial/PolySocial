// Contributors: Claude (Opus 5.5, Anthropic) wrote it after tracing the random sign-up test
// failures to the keyboard opening late, #314; Claude (Opus 5.5, Anthropic) made it close the
// keyboard of the window that has focus (dialogs, bottom sheets) after Compose sent its request,
// wait for its slide to end, and added typeText and replaceText, #427.
package com.polysocial

import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Up to how long the keyboard may take to close; it usually closes in well under a second. */
private const val KEYBOARD_TIMEOUT_MS = 10_000L

/**
 * Types [text] into [field] ([performTextInput]), then closes the keyboard that typing opened
 * ([closeKeyboard]), so that the next tap or check sees the screen as it stays.
 *
 * Device tests type through this or [replaceText], never with `performTextInput` or
 * `performTextReplacement` alone: the keyboard opens after Compose is idle and moves the screen
 * under the next tap, which then does nothing (#314, #427).
 */
fun AndroidComposeTestRule<*, *>.typeText(field: SemanticsNodeInteraction, text: String) {
  field.performTextInput(text)
  closeKeyboard()
}

/**
 * Replaces [field]'s text with [text] ([performTextReplacement]), then closes the keyboard, as
 * [typeText] does.
 */
fun AndroidComposeTestRule<*, *>.replaceText(field: SemanticsNodeInteraction, text: String) {
  field.performTextReplacement(text)
  closeKeyboard()
}

/**
 * Closes the soft keyboard and waits until it has left the screen.
 *
 * The keyboard belongs to another process (the input method, such as Gboard), which answers a text
 * field's focus late: when typing returns, it may not have started opening yet, or still be sliding
 * in. Compose's idling doesn't wait for it, so on a screen with `imePadding()` the test can scroll
 * to a button, then the keyboard shrinks the screen and the tap lands where the button was, or the
 * button leaves the screen right after. [typeText] and [replaceText] call it after typing; call it
 * yourself after anything else that opens the keyboard, such as a tap on a text field, before
 * tapping or checking what is below the fields (#314). It waits for the keyboard to be hidden, the
 * state every device reaches, not for a fixed time or for the keyboard to open: whether and how far
 * it opens depends on the device (a hardware keyboard, a password field). With animations on, it
 * also waits for the keyboard's slide to end (Android 11+): the window's insets give the final
 * state as soon as it starts, but `imePadding()` follows it frame by frame, so the screen still
 * moves until then.
 *
 * It closes the keyboard of the app's window that has focus, the one it types into: the activity's,
 * or, on Android 10+, a dialog's or bottom sheet's above it (the date picker, Delete account).
 * Don't call it while a menu is open: the menu's popup has focus then, so it would close and wait
 * on the popup's window instead of the field's, and replace Compose's own insets callback there.
 */
fun AndroidComposeTestRule<*, *>.closeKeyboard() {
  // A field Compose has just focused asks for the keyboard on the next frame. Letting it ask first
  // means the system gets the hide after it, instead of a hide the request then undoes.
  waitForIdle()
  val activityWindow = activity.window.decorView
  val window = runOnUiThread { focusedWindow() ?: activityWindow }
  val ime = WindowInsetsCompat.Type.ime()
  // The keyboard's slides running in the window, filled by KeyboardSlides (Android 11+).
  val slides = mutableSetOf<WindowInsetsAnimation>()
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
      runOnUiThread { window.setWindowInsetsAnimationCallback(KeyboardSlides(slides)) }
  try {
    // Cancels a keyboard Compose asked for, which goes through the window's insets controller;
    // one asked for only through InputMethodManager can still flash after this (seen on API 34).
    runOnUiThread { SoftwareKeyboardControllerCompat(window).hide() }
    waitUntil("the keyboard is closed", KEYBOARD_TIMEOUT_MS) {
      runOnUiThread {
        // Null once the window is gone (a dialog closed), and its keyboard with it.
        val insets = ViewCompat.getRootWindowInsets(window)
        insets == null ||
            (!insets.isVisible(ime) && insets.getInsets(ime).bottom == 0 && slides.isEmpty())
      }
    }
  } finally {
    // The activity's, a dialog's and a bottom sheet's windows have none of their own there (Compose
    // sets its own on the views below), so removing ours leaves them as they were.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        runOnUiThread { window.setWindowInsetsAnimationCallback(null) }
  }
  // Lets the screen lay out again without the keyboard.
  waitForIdle()
}

/**
 * Keeps in [running] the keyboard's slides (animations) in a window, from the callback set on its
 * root view, which lets the window's own views get theirs too.
 */
@RequiresApi(Build.VERSION_CODES.R)
private class KeyboardSlides(private val running: MutableSet<WindowInsetsAnimation>) :
    WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
  private fun follow(animation: WindowInsetsAnimation) {
    if (animation.typeMask and WindowInsets.Type.ime() != 0) running += animation
  }

  override fun onPrepare(animation: WindowInsetsAnimation) = follow(animation)

  override fun onStart(
      animation: WindowInsetsAnimation,
      bounds: WindowInsetsAnimation.Bounds,
  ): WindowInsetsAnimation.Bounds = bounds.also { follow(animation) }

  // A slide that began before the callback was set shows up here.
  override fun onProgress(
      insets: WindowInsets,
      runningAnimations: MutableList<WindowInsetsAnimation>,
  ): WindowInsets = insets.also { runningAnimations.forEach(::follow) }

  override fun onEnd(animation: WindowInsetsAnimation) {
    running -= animation
  }
}

/**
 * The root view of the app's window that has focus, or null if none has (or, before Android 10,
 * which can't list the app's windows, always).
 */
private fun focusedWindow(): View? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
    else null
