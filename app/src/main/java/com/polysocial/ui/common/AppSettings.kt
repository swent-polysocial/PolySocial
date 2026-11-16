// Contributors: Claude Opus 5.5 (the shared "Open settings" intent, #359); Konstantinos Perdikeas
// (directed, reviewed and owns it).
package com.polysocial.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens this app's page in Android's settings, where a refused permission can be allowed. Returns
 * false when no settings app can open it (a restricted device), so the caller keeps its way back.
 */
fun Context.openAppSettings(): Boolean =
    try {
      startActivity(
          Intent(
                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.fromParts("package", packageName, null),
              )
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      true
    } catch (e: ActivityNotFoundException) {
      false
    }
