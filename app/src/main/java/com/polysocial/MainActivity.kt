// Contributors: Claude (Hilt entry point, light-only system bars and app shell, #41);
// OpenAI Codex (GPT-6.1 Sol, medium; connected sign-up and future authentication destinations).
package com.polysocial

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.polysocial.ui.auth.AuthFlow
import com.polysocial.ui.auth.SignUpViewModel
import com.polysocial.ui.theme.PolySocialTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The app is light only: keep dark system-bar icons even when the phone is in dark mode.
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    setContent {
      PolySocialTheme {
        val signUpViewModel: SignUpViewModel = hiltViewModel()
        AuthFlow(signUpViewModel, onExit = { finish() })
      }
    }
  }
}
