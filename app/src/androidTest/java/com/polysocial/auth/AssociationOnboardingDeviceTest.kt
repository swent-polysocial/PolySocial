// Contributors: OpenAI Codex (default association onboarding and session cleanup integration,
// #125, and the device fixture Firestore binding); Claude Opus 5.5 (longer waits for CI, #309).
// Contributors: Claude (Opus 5.5, Anthropic) bound the deletion and network fakes and expected the
// prefilled address after Change address, #225; Mohamed Khellaf (reviewed and revised).
package com.polysocial.auth

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.MainActivity
import com.polysocial.model.account.AccountDeletionModule
import com.polysocial.model.account.AccountDeletionRepository
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.association.*
import com.polysocial.model.auth.*
import com.polysocial.model.event.*
import com.polysocial.model.firebase.*
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.network.NetworkModule
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.*
import com.polysocial.resources.C
import com.polysocial.ui.association.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.profile.ProfileSetupTestTags
import com.polysocial.ui.profile.ProfileTags
import dagger.hilt.android.testing.*
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@UninstallModules(
    AccountDeletionModule::class,
    AuthModule::class,
    UserProfileModule::class,
    AssociationModule::class,
    EventModule::class,
    FirebaseModule::class,
    NetworkModule::class,
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
  @BindValue @JvmField val firestore: FirebaseFirestore = mockk()
  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val authSessionSource: AuthSessionSource = FakeAuthSessionSource()
  @BindValue @JvmField val sessionOwner: SessionOwnerStore = FakeSessionOwnerStore()
  @BindValue @JvmField val sessions: SessionGuard = FakeSessionGuard()
  @BindValue @JvmField val events: EventRepository = FakeEventRepository()
  private val fakeDeletion = FakeAccountDeletionRepository()
  // Verify Email's Change address deletes the unverified account first (#225).
  @BindValue @JvmField val deletion: AccountDeletionRepository = fakeDeletion
  @BindValue @JvmField val network: NetworkMonitor = FakeNetworkMonitor()
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

        private val addresses = mutableMapOf<String, VerificationTiming>()

        override suspend fun readAddress(email: String) =
            addresses[email.trim().lowercase()] ?: VerificationTiming()

        override suspend fun writeAddress(email: String, timing: VerificationTiming) {
          addresses[email.trim().lowercase()] = timing
        }

        override suspend fun clearAddress(email: String) {
          addresses.remove(email.trim().lowercase())
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
    compose.waitUntil(UI_TIMEOUT_MS) {
      compose.onAllNodesWithTag(VerifyEmailTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    capture("association-verify-email")
    assertTrue(privateProfiles.createdProfiles.isEmpty())
    compose.runOnIdle { fakeAuth.verificationResult = VerificationResult.Verified }
    compose.waitUntil(UI_TIMEOUT_MS) {
      compose
          .onAllNodes(hasTestTag(VerifyEmailTags.Continue) and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    // On a short display the delivery Snackbar can cover a scrolled Continue button.
    compose.waitUntil(UI_TIMEOUT_MS) {
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
    compose.waitUntil(UI_TIMEOUT_MS) { fakeAuth.checkCalls > checksBefore }
    assertTrue(
        "Continue must verify the fixture account",
        requireNotNull(fakeAuth.currentUser()).isEmailVerified,
    )
    compose.waitUntil(UI_TIMEOUT_MS) {
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
    compose.waitUntil(UI_TIMEOUT_MS) {
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
    compose.waitUntil(UI_TIMEOUT_MS) {
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
    compose.waitUntil(UI_TIMEOUT_MS) {
      compose
          .onAllNodes(hasTestTag(VerifyEmailTags.ChangeAddress) and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.waitUntil(UI_TIMEOUT_MS) {
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
    compose.waitUntil(UI_TIMEOUT_MS) {
      compose.onAllNodesWithTag(SignUpTags.Screen).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Your association account").assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Google).assertDoesNotExist()
    assertNull(fakeAuth.currentUser())
    assertEquals(1, fakeAuth.logOutCalls)
    assertEquals(1, fakeDeletion.calls)
    // The deleted account's address is prefilled, so a typo can be fixed in place (#225).
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).assertTextContains(applicant.email)
    capture("association-wrong-address", closeKeyboard = true)
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.Email))
        .performTextReplacement("updated-club@example.org")
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

  private companion object {
    /**
     * How long a step may take to show its result. CI's emulator renders in software and took more
     * than 5 s after sign-up (#309); a passing step doesn't wait this long.
     */
    const val UI_TIMEOUT_MS = 20_000L
  }
}
