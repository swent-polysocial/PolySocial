// Contributors: Claude (Hilt entry point, light-only system bars and app shell, #41);
// OpenAI Codex (GPT-6.1 Sol, medium; connected sign-up and future authentication destinations).
// Contributors: Claude Opus 5.5 (opened a reminder's event on notification tap, cold and warm,
// second PR of #123's review).
// Contributors: Claude Opus 5.5 (cleared the consumed notification's extra from the retained
// Intent so recreation doesn't replay it, #123's second PR, Mehdi's review).
package com.polysocial

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.polysocial.model.reminder.NOTIFICATION_EVENT_ID_EXTRA
import com.polysocial.ui.auth.AuthFlow
import com.polysocial.ui.auth.SignUpViewModel
import com.polysocial.ui.theme.PolySocialTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  /**
   * The event ID of the reminder notification that opened or resumed this activity, or null for an
   * ordinary launch. `singleTop` (manifest) delivers a later tap to [onNewIntent] instead of a new
   * instance, so both a cold and a warm launch land here. [AuthFlow] consumes it once navigated.
   * Internal, not private, so a test can read it directly instead of only through the UI.
   */
  internal var pendingReminderEventId by mutableStateOf<String?>(null)

  /**
   * Clears [pendingReminderEventId] and the extra it came from on [getIntent], so a later
   * recreation (for example a rotation) reading that same retained [Intent] in [onCreate] doesn't
   * resurrect an event the student already navigated away from.
   */
  internal fun consumePendingReminderEventId() {
    pendingReminderEventId = null
    intent.removeExtra(NOTIFICATION_EVENT_ID_EXTRA)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    pendingReminderEventId = intent.getStringExtra(NOTIFICATION_EVENT_ID_EXTRA)
    // The app is light only: keep dark system-bar icons even when the phone is in dark mode.
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    setContent {
      PolySocialTheme {
        val signUpViewModel: SignUpViewModel = hiltViewModel()
        AuthFlow(
            signUpViewModel,
            onExit = { finish() },
            pendingReminderEventId = pendingReminderEventId,
            onPendingReminderEventIdConsumed = ::consumePendingReminderEventId,
        )
      }
    }
  }

  public override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.getStringExtra(NOTIFICATION_EVENT_ID_EXTRA)?.let { pendingReminderEventId = it }
  }
}
