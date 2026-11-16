// Contributors: OpenAI Codex (emulator setup regression and screenshots, #125).
// Contributors: Claude (Opus 5.5, Anthropic) typed through typeText, which closes the keyboard
// and waits until it is gone, instead of hiding it without waiting, #427.
package com.polysocial.ui.association

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.polysocial.model.association.*
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.typeText
import com.polysocial.ui.theme.PolySocialTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssociationSetupDeviceTest {
  // An Android rule, which typeText() needs: the same activity createComposeRule() starts.
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun formValidationAndOfflineRetryKeepTheAssociationDetails() {
    val auth = FakeAuthRepository(AuthUser("example-club", "club@example.org", true))
    val profiles = FakeUserProfileRepository()
    val associations =
        FakeAssociationRepository().apply {
          createAssociationResult = CreateAssociationResult.NetworkError
        }
    val model = AssociationSetupViewModel(auth, profiles, associations, SavedStateHandle())
    var completions = 0
    compose.setContent { PolySocialTheme { AssociationSetupRoute({}, { completions++ }, model) } }
    compose.onNodeWithTag(AssociationSetupTags.Submit).performScrollTo().performClick()
    assertTrue(profiles.createdProfiles.isEmpty())
    for (field in
        listOf(
            AssociationSetupField.Name,
            AssociationSetupField.Type,
            AssociationSetupField.About,
        )) compose.onNodeWithTag("${AssociationSetupTags.field(field)}_error").assertExists()
    compose.onNodeWithTag(AssociationSetupTags.Back).performScrollTo()
    capture("association-setup-validation")
    compose.typeText(
        compose
            .onNodeWithTag(AssociationSetupTags.field(AssociationSetupField.Name))
            .performScrollTo(),
        "Example Club",
    )
    compose
        .onNodeWithTag(AssociationSetupTags.type(AssociationType.CLUB))
        .performScrollTo()
        .performClick()
    compose.typeText(
        compose
            .onNodeWithTag(AssociationSetupTags.field(AssociationSetupField.About))
            .performScrollTo(),
        "Meet students through campus activities.",
    )
    compose.typeText(
        compose
            .onNodeWithTag(AssociationSetupTags.field(AssociationSetupField.Website))
            .performScrollTo(),
        "https://example.org",
    )
    compose.onNodeWithTag(AssociationSetupTags.Back).performScrollTo()
    compose.onNodeWithTag(AssociationSetupTags.Initials).assertTextEquals("EC")
    val notice =
        compose
            .onNodeWithTag(AssociationSetupTags.ReviewNotice)
            .performScrollTo()
            .captureToImage()
            .asAndroidBitmap()
    assertEquals(0xFFFDF1DC.toInt(), notice.getPixel(notice.width / 2, 3))
    compose.onNodeWithTag(AssociationSetupTags.Back).performScrollTo()
    capture("association-setup-filled")
    compose.onNodeWithTag(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(AssociationSetupTags.Error).performScrollTo().assertIsDisplayed()
    assertEquals("Example Club", model.uiState.value.form.name)
    assertEquals(0, completions)
    capture("association-setup-offline")
    compose.runOnIdle {
      associations.createAssociationResult = CreateAssociationResult.Created
      profiles.getProfileResult = ProfileResult.Found(profiles.createdProfiles.single())
    }
    compose.onNodeWithTag(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(1, completions)
    assertEquals(AccountType.ASSOCIATION, profiles.createdProfiles.single().accountType)
    assertEquals("https://example.org", associations.createdAssociations.last().website)
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory =
        File(requireNotNull(context.getExternalFilesDir(null)), "pr-screenshots").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { stream ->
      assertTrue(
          compose
              .onRoot()
              .captureToImage()
              .asAndroidBitmap()
              .compress(Bitmap.CompressFormat.PNG, 100, stream)
      )
    }
  }
}
