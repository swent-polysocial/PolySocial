// Contributors: OpenAI Codex (configured privacy contact through the default Android wiring, #103).
package com.polysocial.ui.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import io.mockk.every
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises resource configuration, the route factory and Android launcher without opening an app.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyContactRouteTest {
  @get:Rule val rule = createComposeRule()

  private class EmailContext :
      ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
    var missingClient = false
    val drafts = mutableListOf<Intent>()

    override fun getApplicationContext(): Context = this

    override fun startActivity(intent: Intent) {
      if (missingClient) throw ActivityNotFoundException()
      drafts += Intent(intent)
    }
  }

  private fun showConfiguredNotice(context: EmailContext) {
    val resources: Resources = spyk(context.resources)
    every { resources.getString(R.string.privacy_contact_email) } returns "team@example.invalid"
    rule.setContent {
      CompositionLocalProvider(LocalContext provides context, LocalResources provides resources) {
        PolySocialTheme { FullPrivacyNoticeRoute(onBack = {}) }
      }
    }
  }

  @Test
  fun configuredResourceOpensTheAndroidDraftWithoutAccountDetails() {
    val context = EmailContext()
    showConfiguredNotice(context)

    rule.onNodeWithTag(C.Tag.privacy_contact).performScrollTo().performClick()

    val draft = context.drafts.single()
    assertEquals(Intent.ACTION_SENDTO, draft.action)
    assertEquals("mailto", draft.data?.scheme)
    assertEquals("team@example.invalid", draft.data?.schemeSpecificPart)
    assertEquals(
        context.getString(R.string.privacy_contact_subject),
        draft.getStringExtra(Intent.EXTRA_SUBJECT),
    )
    assertNull(draft.getStringExtra(Intent.EXTRA_TEXT))
    rule.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
  }

  @Test
  fun missingAndroidClientKeepsTheNoticeAndAllowsRetry() {
    val context = EmailContext().apply { missingClient = true }
    showConfiguredNotice(context)
    rule.onNodeWithTag(C.Tag.privacy_contact).performScrollTo().performClick()
    rule.onNodeWithText(context.getString(R.string.privacy_contact_no_app)).assertIsDisplayed()
    assertTrue(context.drafts.isEmpty())

    rule.onNodeWithText(context.getString(R.string.privacy_dismiss)).performClick()
    context.missingClient = false
    rule.onNodeWithTag(C.Tag.privacy_contact).performClick()

    assertEquals(1, context.drafts.size)
    rule.onNodeWithText(context.getString(R.string.privacy_contact_no_app)).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
  }
}
