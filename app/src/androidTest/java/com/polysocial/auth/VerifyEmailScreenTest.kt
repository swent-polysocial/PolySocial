// Contributors: OpenAI Codex (verification UI, Back sign-out and session/navigation regression
// tests for #31;
// synchronized Continue checks with startup work and semantic actions for PR #99's CI failures).
package com.polysocial.auth

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import io.mockk.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerifyEmailScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val user = AuthUser("test-uid", "student.test@epfl.ch", false)
  private val repository = mockk<AuthRepository>()
  private val timings = mutableMapOf<String, VerificationTiming>()
  private val store =
      object : VerificationStore {
        override suspend fun read(uid: String) = timings[uid] ?: VerificationTiming()

        override suspend fun write(uid: String, timing: VerificationTiming) {
          timings[uid] = timing
        }

        override suspend fun clear(uid: String) {
          timings.remove(uid)
        }
      }
  private val now = 1_000_000L
  private lateinit var vm: VerifyEmailViewModel
  private lateinit var signup: SignUpViewModel
  private lateinit var login: LoginViewModel
  private lateinit var start: AppStartViewModel
  private val profiles = mockk<UserProfileRepository>()

  @After
  fun disposeVerification() {
    ViewModelStore().apply {
      put("verification", vm)
      clear()
    }
  }

  @Before
  fun setup() {
    every { repository.currentUser() } returns user
    every { repository.logOut() } answers { every { repository.currentUser() } returns null }
    coEvery { repository.sendVerificationEmail() } returns SendVerificationResult.Sent
    coEvery { repository.reloadAndCheckVerified() } returns VerificationResult.Unverified
    coEvery { profiles.getProfile(user.uid) } returns ProfileResult.NotFound
    start = AppStartViewModel(repository, profiles)
    vm = VerifyEmailViewModel(repository, store, VerificationClock { now })
    signup = SignUpViewModel(repository)
    login = LoginViewModel(repository)
  }

  private fun flow() {
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signup,
            onExit = {},
            loginViewModel = login,
            verificationViewModel = vm,
            startViewModel = start,
        )
      }
    }
  }

  private fun message(id: Int) = compose.activity.getString(id)

  @Test
  fun unverifiedRestartStartsAtVerificationWithoutSignUpOrLoginInBackHistory() {
    timings[user.uid] = VerificationTiming(now - 15_000, now + 30_000)
    flow()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Email).assertTextEquals(user.email)
    compose
        .onNodeWithTag(VerifyEmailTags.Resend)
        .performScrollTo()
        .assertIsNotEnabled()
        .assertTextContains("0:30", substring = true)
    compose.onNodeWithText(message(R.string.verification_already_sent)).assertIsDisplayed()
    coVerify(exactly = 0) { repository.sendVerificationEmail() }
    compose.onNodeWithTag(VerifyEmailTags.Back).performScrollTo().performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Screen).assertDoesNotExist()
    verify(exactly = 1) { repository.logOut() }
  }

  @Test
  fun systemBackFromVerificationSignsOutAndReturnsToWelcome() {
    flow()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    verify(exactly = 1) { repository.logOut() }
  }

  @Test
  fun browserVerificationFromVerificationContinuesToProfilePlaceholderOnResume() {
    flow()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    coEvery { repository.reloadAndCheckVerified() } answers
        {
          every { repository.currentUser() } returns user.copy(isEmailVerified = true)
          VerificationResult.Verified
        }
    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    coVerify(exactly = 0) { repository.logIn(any(), any()) }
    verify(exactly = 0) { repository.logOut() }
  }

  @Test
  fun continueKeepsUnverifiedUserOnScreenThenCompletesWithoutLogin() {
    val initialSend = CompletableDeferred<Unit>()
    coEvery { repository.sendVerificationEmail() } coAnswers
        {
          initialSend.await()
          SendVerificationResult.Sent
        }
    flow()
    compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.sending }
    compose.onNodeWithTag(VerifyEmailTags.Continue).assertIsNotEnabled()
    // Startup send and resume checks disable Continue; a click during that work is ignored.
    initialSend.complete(Unit)
    val readyToContinue = hasTestTag(VerifyEmailTags.Continue) and isEnabled()
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodes(readyToContinue).fetchSemanticsNodes().isNotEmpty()
    }
    // Exercise the button's action directly; enabled semantics alone do not guarantee a touch
    // reaches it while the confirmation snackbar is displayed over a small window.
    compose.onNodeWithTag(VerifyEmailTags.Continue).performSemanticsAction(
        SemanticsActions.OnClick
    ) {
      it()
    }
    val unverifiedMessage = message(R.string.verification_unverified)
    compose.waitUntil(timeoutMillis = 5_000) {
      compose
          .onAllNodes(hasTestTag(VerifyEmailTags.Banner) and hasText(unverifiedMessage))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.onNodeWithTag(VerifyEmailTags.Banner).assertTextContains(unverifiedMessage)
    compose.onNodeWithTag("auth_profile_setup").assertDoesNotExist()
    coEvery { repository.reloadAndCheckVerified() } answers
        {
          every { repository.currentUser() } returns user.copy(isEmailVerified = true)
          VerificationResult.Verified
        }
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodes(readyToContinue).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(VerifyEmailTags.Continue).performSemanticsAction(
        SemanticsActions.OnClick
    ) {
      it()
    }
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodesWithTag("auth_profile_setup").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    coVerify(exactly = 0) { repository.logIn(any(), any()) }
  }

  @Test
  fun changeAddressSignsOutAndOpensFreshSignUpForm() {
    flow()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).assertTextEquals("")
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertDoesNotExist()
    verify(exactly = 1) { repository.logOut() }
  }

  @Test
  fun resendShowsDistinctThrottleBannerAndDisabledCountdown() {
    coEvery { repository.sendVerificationEmail() } returns SendVerificationResult.Throttled
    flow()
    compose
        .onNodeWithTag(VerifyEmailTags.Banner)
        .assertTextContains(message(R.string.verification_throttled))
    compose
        .onNodeWithTag(VerifyEmailTags.Resend)
        .performScrollTo()
        .assertIsNotEnabled()
        .assertTextContains("3:00", substring = true)
    compose.onNodeWithTag(VerifyEmailTags.Continue).assertIsEnabled()
  }

  @Test
  fun sentEmailShowsConfirmationAndCooldown() {
    flow()
    compose
        .onNodeWithTag(VerifyEmailTags.Banner)
        .assertTextContains(message(R.string.verification_sent))
    compose
        .onNodeWithTag(VerifyEmailTags.Resend)
        .performScrollTo()
        .assertIsNotEnabled()
        .assertTextContains("0:45", substring = true)
    compose.onNodeWithTag(VerifyEmailTags.Continue).assertIsEnabled()
    coVerify(exactly = 1) { repository.sendVerificationEmail() }
  }

  @Test
  fun networkFailureShowsRetryWithoutClaimingDelivery() {
    coEvery { repository.sendVerificationEmail() } returns SendVerificationResult.NetworkError
    flow()
    compose
        .onNodeWithTag(VerifyEmailTags.Banner)
        .assertTextContains(message(R.string.verification_network))
    compose.onNodeWithText(message(R.string.verification_description)).assertDoesNotExist()
    compose.onNodeWithTag(VerifyEmailTags.Resend).performScrollTo().assertIsEnabled()
    coEvery { repository.sendVerificationEmail() } returns SendVerificationResult.Sent
    compose.onNodeWithTag(VerifyEmailTags.Resend).performClick()
    compose
        .onNodeWithTag(VerifyEmailTags.Banner)
        .assertTextContains(message(R.string.verification_sent))
  }

  @Test
  fun loadingStatesDisableControlsAndExposeProgress() {
    compose.setContent {
      PolySocialTheme {
        VerifyEmailScreen(
            VerifyEmailUiState(email = user.email, sending = true),
            {},
            {},
            {},
            {},
            {},
        )
      }
    }
    compose.onNodeWithTag(VerifyEmailTags.Resend).performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag(VerifyEmailTags.Loading).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Continue).assertIsNotEnabled()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).assertIsNotEnabled()
  }

  @Test
  fun verificationMatchesFigmaLayoutWithBundledIcons() {
    compose.setContent {
      PolySocialTheme {
        VerifyEmailScreen(
            VerifyEmailUiState(email = user.email, hasSentEmail = true, remainingSeconds = 45),
            {},
            {},
            {},
            {},
            {},
        )
      }
    }
    compose.onNodeWithTag(VerifyEmailTags.Email).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Continue).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Resend).assertIsDisplayed()
    // Preserve a synthetic-account render for visual QA; no live backend is involved.
    val target = File(compose.activity.getExternalFilesDir(null), "verification-qa.png")
    target.outputStream().use {
      assertTrue(
          compose
              .onNodeWithTag(VerifyEmailTags.Screen)
              .captureToImage()
              .asAndroidBitmap()
              .compress(Bitmap.CompressFormat.PNG, 100, it)
      )
    }
  }
}
