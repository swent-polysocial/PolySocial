// Contributors: OpenAI Codex (Android email draft and unavailable-client handling, #103).
package com.polysocial.model.privacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Uses email-only Android intent handling, without reading or sending personal data. */
class AndroidPrivacyContactLauncher(private val context: Context) : PrivacyContactLauncher {
  override fun openDraft(address: String, subject: String): Boolean =
      try {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
      } catch (_: ActivityNotFoundException) {
        false
      } catch (_: SecurityException) {
        false
      }
}
