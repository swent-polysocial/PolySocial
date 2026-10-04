// Contributors: Claude (Hilt application entry point).
package com.polysocial

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Application class that starts Hilt's dependency graph for the whole app. */
@HiltAndroidApp class PolySocialApp : Application()
