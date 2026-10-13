// Contributors: OpenAI Codex (private contact draft boundary, #103).
package com.polysocial.model.privacy

/** Opens an email draft chosen by the user; never sends mail or adds account data. */
fun interface PrivacyContactLauncher {
  /** Returns false when an email app cannot be opened. */
  fun openDraft(address: String, subject: String): Boolean
}
