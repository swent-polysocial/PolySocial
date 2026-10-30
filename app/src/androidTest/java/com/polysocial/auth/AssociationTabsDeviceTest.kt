// Contributors: OpenAI Codex (session-guard fake for rebased association integration tests).
// Contributors: OpenAI Codex (association activity recreation, navigation and logout regression,
// #140).
package com.polysocial.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import com.polysocial.model.auth.AuthSessionSource
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.FakeAuthSessionSource
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.firebase.FakeSessionOwnerStore
import com.polysocial.model.firebase.FirebaseModule
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.firebase.SessionGuard
import com.polysocial.model.firebase.SessionOwnerStore
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
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
  @BindValue @JvmField val authSessionSource: AuthSessionSource = FakeAuthSessionSource()
  @BindValue @JvmField val sessionOwner: SessionOwnerStore = FakeSessionOwnerStore()
  @BindValue @JvmField val sessions: SessionGuard = FakeSessionGuard()
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
  fun privacyAndOrganizationSurviveActivityRecreationThenLogoutResetsTheSession() {
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(C.Tag.app_shell).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.nav_item_organization).performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(OrganizationTags.Verified).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText(identity.name).assertIsDisplayed()
    val readsBeforePrivacy = identities.requestedUids.toList()
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()

    // Exercise the real Activity saved state and retained navigation ViewModel stores.
    compose.activityRule.scenario.recreate()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(C.Tag.privacy_screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(OrganizationTags.Verified).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.nav_item_organization).assertIsSelected()
    compose.onNodeWithTag(C.Tag.nav_item_chats).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_profile).assertDoesNotExist()
    compose.onNodeWithText(identity.name).performScrollTo().assertIsDisplayed()
    assertEquals(
        "Returning to Organization reuses this account's loaded identity",
        readsBeforePrivacy,
        identities.requestedUids,
    )

    compose.onNodeWithTag(ProfileTags.Logout).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(WelcomeTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(1, clears)
    assertEquals(1, fakeAuth.logOutCalls)
    assertNull(fakeAuth.currentUser())
    assertTrue(identities.requestedUids.all { it == user.uid })
  }
}
