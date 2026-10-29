// Contributors: OpenAI Codex (default association onboarding and session cleanup integration,
// #125).
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
import com.polysocial.ui.profile.ProfileSetupTestTags
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
class AssociationOnboardingInjectionTest {
  private val applicant = AuthUser("association-test", "club@example.org", false)
  private val fakeAuth =
      FakeAuthRepository().apply { signUpResult = SignUpResult.Success(applicant) }
  private val privateProfiles = FakeUserProfileRepository()
  private val identities = FakeAssociationRepository()
  private val writes = mutableListOf<String>()
  private var clears = 0
  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val events: EventRepository = FakeEventRepository()
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
  @BindValue @JvmField val verificationClock: VerificationClock = VerificationClock { 1000 }
  @BindValue
  @JvmField
  val signupStore: AssociationSignupStore =
      object : AssociationSignupStore {
        private val emails = mutableSetOf<String>()

        override suspend fun remember(email: String) {
          emails += email.trim().lowercase()
        }

        override suspend fun contains(email: String) = email.trim().lowercase() in emails

        override suspend fun clear(email: String) {
          emails -= email.trim().lowercase()
        }
      }
  @BindValue
  @JvmField
  val cache: SessionCache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          return CacheClearResult.Cleared
        }
      }
  @BindValue
  @JvmField
  val profiles: UserProfileRepository =
      object : UserProfileRepository by privateProfiles {
        override suspend fun createProfile(profile: UserProfile): CreateProfileResult {
          writes += "users"
          return privateProfiles.createProfile(profile).also {
            if (it == CreateProfileResult.Created)
                privateProfiles.getProfileResult = ProfileResult.Found(profile)
          }
        }
      }
  @BindValue
  @JvmField
  val associations: AssociationRepository =
      object : AssociationRepository by identities {
        override suspend fun createAssociation(association: Association): CreateAssociationResult {
          writes += "association"
          return identities.createAssociation(association).also {
            if (it == CreateAssociationResult.Created)
                identities.getAssociationResult = AssociationResult.Found(association)
          }
        }
      }
  @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun defaultRoutesSaveTheAssociationInOrderAndLogoutClearsTheSession() {
    compose.onNodeWithTag(WelcomeTags.Association).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performTextInput(applicant.email)
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performScrollTo()
        .performTextInput("password1")
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    assertTrue(privateProfiles.createdProfiles.isEmpty())
    compose.runOnIdle { fakeAuth.verificationResult = VerificationResult.Verified }
    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().performClick()
    compose.onNodeWithTag(AssociationSetupTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertDoesNotExist()
    compose
        .onNodeWithTag(AssociationSetupTags.field(AssociationSetupField.Name))
        .performScrollTo()
        .performTextInput("Example Club")
    compose
        .onNodeWithTag(AssociationSetupTags.type(AssociationType.CLUB))
        .performScrollTo()
        .performClick()
    compose
        .onNodeWithTag(AssociationSetupTags.field(AssociationSetupField.About))
        .performScrollTo()
        .performTextInput("Fictional campus activities")
    compose
        .onNodeWithTag(AssociationSetupTags.field(AssociationSetupField.Website))
        .performScrollTo()
        .performTextInput("https://example.org")
    compose.onNodeWithTag(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileTags.Logout).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(listOf("users", "association"), writes)
    assertEquals(AccountType.ASSOCIATION, privateProfiles.createdProfiles.single().accountType)
    assertFalse(privateProfiles.createdProfiles.single().isAssociationVerified)
    assertEquals("Example Club", identities.createdAssociations.single().name)
    assertEquals("https://example.org", identities.createdAssociations.single().website)
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(1, clears)
    assertEquals(1, fakeAuth.logOutCalls)
    assertNull(fakeAuth.user)
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertTrue(compose.activity.isFinishing)
  }
}
