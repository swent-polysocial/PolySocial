// Contributors: Claude (Hilt application entry point).
// Contributors: Claude (Opus 5.5, Anthropic) started the session guard for the process, #198;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial

import android.app.Application
import com.polysocial.model.firebase.FirestoreSessionGuard
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application class that starts Hilt's dependency graph for the whole app, and the session guard
 * that keeps one account from reading another account's offline cache (#198). The guard watches
 * sign-ins and sign-outs for the life of the process, so it starts here once, not per activity.
 */
@HiltAndroidApp
class PolySocialApp : Application() {
  @Inject lateinit var sessionGuard: FirestoreSessionGuard

  override fun onCreate() {
    super.onCreate()
    // Main.immediate: the guard blocks the cache inside Firebase's main-thread auth callback.
    sessionGuard.start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
  }
}
