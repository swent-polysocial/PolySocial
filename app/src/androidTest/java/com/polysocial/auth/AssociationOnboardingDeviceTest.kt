// Contributors: OpenAI Codex (default association onboarding and session cleanup integration,
// #125).
package com.polysocial.auth

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.polysocial.ui.profile.ProfileSetupTestTags
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
class AssociationOnboardingDeviceTest {
  private val applicant = AuthUser("association-test", "club@example.org", false)
  private val fakeAuth =
      FakeAuthRepository().apply { signUpResult = SignUpResult.Success(applicant) }
  private val privateProfiles = FakeUserProfileRepository()
  private val identities = FakeAssociationRepository()
  private val writes = mutableListOf<String>()
  private var clears = 0
  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val authSessionSource: AuthSessionSource = FakeAuthSessionSource()
  @BindValue @JvmField val sessionOwner: SessionOwnerStore = FakeSessionOwnerStore()
  @BindValue @JvmField val sessions: SessionGuard = FakeSessionGuard()
  @BindValue @JvmField val events: EventRepository = FakeEventRepository()
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
    capture("association-signup", closeKeyboard = true)
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(VerifyEmailTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    capture("association-verify-email")
    assertTrue(privateProfiles.createdProfiles.isEmpty())
    compose.runOnIdle { fakeAuth.verificationResult = VerificationResult.Verified }
    compose.waitUntil(5000) {
      compose
          .onAllNodes(hasTestTag(VerifyEmailTags.Continue) and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    // On a short display the delivery Snackbar can cover a scrolled Continue button.
    compose.waitUntil(10000) {
      compose
          .onAllNodes(hasAnyAncestor(hasTestTag(VerifyEmailTags.Snackbar)))
          .fetchSemanticsNodes()
          .isEmpty()
    }
    val checksBefore = fakeAuth.checkCalls
    compose
        .onNodeWithTag(VerifyEmailTags.Continue)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    compose.waitUntil(5000) { fakeAuth.checkCalls > checksBefore }
    assertTrue(
        "Continue must verify the fixture account",
        requireNotNull(fakeAuth.currentUser()).isEmailVerified,
    )
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(AssociationSetupTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
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
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(AssociationTags.UNDER_REVIEW).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(AssociationTags.UNDER_REVIEW).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).performClick()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(listOf("users", "association"), writes)
    assertEquals(AccountType.ASSOCIATION, privateProfiles.createdProfiles.single().accountType)
    assertFalse(privateProfiles.createdProfiles.single().isAssociationVerified)
    assertEquals("Example Club", identities.createdAssociations.single().name)
    assertEquals("https://example.org", identities.createdAssociations.single().website)
    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(WelcomeTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(1, clears)
    assertEquals(1, fakeAuth.logOutCalls)
    assertNull(fakeAuth.user)
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertTrue(compose.activity.isFinishing)
  }

  @Test
  fun wrongAddressReturnsToAssociationAccountCreationAndAcceptsAnotherNonEpflEmail() {
    compose.onNodeWithTag(WelcomeTags.Association).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performTextInput(applicant.email)
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performScrollTo()
        .performTextInput("password1")
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.waitUntil(5000) {
      compose
          .onAllNodes(hasTestTag(VerifyEmailTags.ChangeAddress) and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.waitUntil(10000) {
      compose
          .onAllNodes(hasAnyAncestor(hasTestTag(VerifyEmailTags.Snackbar)))
          .fetchSemanticsNodes()
          .isEmpty()
    }
    compose
        .onNodeWithTag(VerifyEmailTags.ChangeAddress)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag(SignUpTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Your association account").assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Google).assertDoesNotExist()
    assertNull(fakeAuth.currentUser())
    assertEquals(1, fakeAuth.logOutCalls)
    capture("association-wrong-address", closeKeyboard = true)
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.Email))
        .performTextInput("updated-club@example.org")
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performScrollTo()
        .performTextInput("password1")
    compose.onNodeWithTag(SignUpTags.error(SignUpField.Email)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsEnabled()
    assertEquals(1, fakeAuth.signUpCalls)
  }

  private fun capture(name: String, closeKeyboard: Boolean = false) {
    if (closeKeyboard)
        compose.runOnIdle {
          compose.activity
              .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
              .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
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
