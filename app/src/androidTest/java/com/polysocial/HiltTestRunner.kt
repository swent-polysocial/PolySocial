// Contributors: Claude (instrumented-test runner for Hilt, #136).
package com.polysocial

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Runs the instrumented tests in [HiltTestApplication] instead of [PolySocialApp], so a
 * `@HiltAndroidTest` can replace the app's modules with fakes. Tests that launch an
 * `@AndroidEntryPoint` activity must therefore be `@HiltAndroidTest` with a `HiltAndroidRule`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
  override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
      super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
