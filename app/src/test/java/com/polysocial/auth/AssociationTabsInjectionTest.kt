// Contributors: OpenAI Codex (approved association tabs and session cleanup integration, #140).
package com.polysocial.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@UninstallModules(
    AuthModule::class,
    UserProfileModule::class,
    AssociationModule::class,
    EventModule::class,
    FirebaseModule::class,
)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class AssociationTabsInjectionTest {
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
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
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
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.nav_item_chats).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_profile).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(C.Tag.screen_map).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Map — coming soon").assertIsDisplayed()
    compose.onNodeWithTag("nav_item_organization").performClick()
    compose.onNodeWithText(identity.name).assertIsDisplayed()
    compose.onNodeWithTag(OrganizationTags.Verified).assertIsDisplayed()
    compose.onNodeWithTag(OrganizationTags.Members).performScrollTo().performClick()
    compose.onNodeWithText("Not available yet").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(OrganizationTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Logout).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(1, clears)
    assertEquals(1, fakeAuth.logOutCalls)
    assertNull(fakeAuth.currentUser())
    assertTrue(identities.requestedUids.all { it == user.uid })
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertTrue(compose.activity.isFinishing)
  }
}
