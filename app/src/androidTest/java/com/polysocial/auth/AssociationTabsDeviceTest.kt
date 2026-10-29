// Contributors: OpenAI Codex (approved association tabs and session cleanup integration, #140).
package com.polysocial.auth

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.polysocial.MainActivity
import com.polysocial.model.association.*
import com.polysocial.model.auth.*
import com.polysocial.model.event.*
import com.polysocial.model.firebase.*
import com.polysocial.model.user.*
import com.polysocial.resources.C
import com.polysocial.ui.association.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.profile.ProfileTags
import dagger.hilt.android.testing.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@UninstallModules(
    AuthModule::class,
    UserProfileModule::class,
    AssociationModule::class,
    EventModule::class,
    FirebaseModule::class,
)
@RunWith(AndroidJUnit4::class)
class AssociationTabsDeviceTest {
  private val user = AuthUser("club", "club@example.org", true)
  private val identity =
      Association(
          "club",
          "Example Club",
          AssociationType.CLUB,
          "Campus activities",
          "https://example.org",
      )
  private val fakeAuth = FakeAuthRepository(user)
  private val identities = FakeAssociationRepository(AssociationResult.Found(identity))
  private var clears = 0
  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val associations: AssociationRepository = identities
  @BindValue
  @JvmField
  val events: EventRepository =
      FakeEventRepository(currentUid = "club", creatorIsVerifiedAssociation = true)
  @BindValue
  @JvmField
  val profiles: UserProfileRepository =
      FakeUserProfileRepository(
          ProfileResult.Found(
              UserProfile(
                  user.uid,
                  user.email,
                  "Example Club",
                  "",
                  "",
                  AccountType.ASSOCIATION,
                  true,
              )
          )
      )
  @BindValue
  @JvmField
  val verificationStore: VerificationStore =
      object : VerificationStore {
        private val values = mutableMapOf<String, VerificationTiming>()

        override suspend fun read(uid: String) = values[uid] ?: VerificationTiming()

        override suspend fun write(uid: String, timing: VerificationTiming) {
          values[uid] = timing
        }

        override suspend fun clear(uid: String) {
          values.remove(uid)
        }
      }
  @BindValue @JvmField val verificationClock: VerificationClock = VerificationClock { 1000 }
  @BindValue @JvmField val signupStore: AssociationSignupStore = EmptyAssociationSignupStore
  @BindValue
  @JvmField
  val cache: SessionCache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          return CacheClearResult.Cleared
        }
      }
  @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun approvedAssociationUsesRealOrganizationPrivacyAndLogoutWithoutStudentScreens() {
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(C.Tag.app_shell).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.nav_item_chats).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_profile).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(C.Tag.screen_map).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Map — coming soon").assertIsDisplayed()
    compose.onNodeWithTag("nav_item_organization").performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithText(identity.name).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText(identity.name).assertIsDisplayed()
    compose.onNodeWithTag(OrganizationTags.Verified).assertIsDisplayed()
    capture("organization")
    compose.onNodeWithTag(OrganizationTags.Members).performScrollTo().performClick()
    compose.onNodeWithText("Not available yet").assertIsDisplayed()
    capture("organization-unavailable")
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(OrganizationTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Logout).performScrollTo().performClick()
    capture("organization-logout")
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(WelcomeTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(1, clears)
    assertEquals(1, fakeAuth.logOutCalls)
    assertNull(fakeAuth.currentUser())
    assertTrue(identities.requestedUids.all { it == user.uid })
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertTrue(compose.activity.isFinishing)
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val folder =
        File(requireNotNull(context.getExternalFilesDir(null)), "pr-screenshots").apply { mkdirs() }
    val screenshot =
        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    File(folder, "$name.png").outputStream().use {
      assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
  }
}
