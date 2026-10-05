// Contributors: Claude (Hilt @AndroidEntryPoint annotation, #70; light system-bar icons for the
// light-only theme, #66; show the app shell instead of the template greeting, #41).
package com.polysocial

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.polysocial.ui.navigation.AppShell
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
    setContent { PolySocialTheme { AppShell() } }
  }
}
