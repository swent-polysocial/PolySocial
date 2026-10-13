// Contributors: OpenAI Codex (email draft and unavailable-client regression tests, #103).
package com.polysocial.model.privacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPrivacyContactLauncherTest {
  private class EmailContext(private val failure: RuntimeException? = null) :
      ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
    var openedIntent: Intent? = null

    override fun startActivity(intent: Intent) {
      failure?.let { throw it }
      openedIntent = intent
    }
  }

  @Test
  fun opensAnEmailDraftWithoutCollectingAccountData() {
    val context = EmailContext()
    assertTrue(
        AndroidPrivacyContactLauncher(context).openDraft("team@example.invalid", "Data request")
    )
    val draft = requireNotNull(context.openedIntent)
    assertEquals(Intent.ACTION_SENDTO, draft.action)
    assertEquals("mailto", draft.data?.scheme)
    assertEquals("team@example.invalid", draft.data?.schemeSpecificPart)
    assertEquals("Data request", draft.getStringExtra(Intent.EXTRA_SUBJECT))
    assertNull(draft.getStringExtra(Intent.EXTRA_TEXT))
    assertTrue(draft.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun aMissingEmailAppDoesNotCrash() {
    val context = EmailContext(ActivityNotFoundException())
    assertFalse(AndroidPrivacyContactLauncher(context).openDraft("team@example.invalid", "Request"))
    assertNull(context.openedIntent)
  }

  @Test
  fun aBlockedEmailAppDoesNotCrash() {
    val context = EmailContext(SecurityException())
    assertFalse(AndroidPrivacyContactLauncher(context).openDraft("team@example.invalid", "Request"))
    assertNull(context.openedIntent)
  }
}
