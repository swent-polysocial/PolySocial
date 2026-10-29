// Contributors: OpenAI Codex (approved association tabs, Privacy return caching and session
// cleanup, #140).
package com.polysocial.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationModule
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.AuthModule
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.FirebaseModule
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileModule
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.association.OrganizationTags
import com.polysocial.ui.auth.WelcomeTags
import com.polysocial.ui.profile.ProfileTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    compose.onNodeWithText("Your events map is not available yet").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.nav_item_organization).performClick()
    compose.onNodeWithText(identity.name).assertIsDisplayed()
    compose.onNodeWithTag(OrganizationTags.Verified).assertIsDisplayed()
    compose.onNodeWithTag(OrganizationTags.Members).performScrollTo().performClick()
    compose.onNodeWithText("Not available yet").assertIsDisplayed()
    val identityReadsBeforePrivacy = identities.requestedUids.toList()
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(OrganizationTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.loading_state).assertDoesNotExist()
    compose.onNodeWithText(identity.name).performScrollTo().assertIsDisplayed()
    assertEquals(identityReadsBeforePrivacy, identities.requestedUids)
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
