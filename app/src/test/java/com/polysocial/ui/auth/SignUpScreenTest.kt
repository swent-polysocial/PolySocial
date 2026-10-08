// Contributors: OpenAI Codex (tested name hints, recovery layout and field-specific errors).
package com.polysocial.ui.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.*
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignUpScreenTest {
  @get:Rule val compose = createComposeRule()
  private val auth = FakeAuthRepository()
  private val viewModel = SignUpViewModel(auth)
  private var loginCalls = 0

  private fun launch() {
    compose.setContent { PolySocialTheme { SignUpRoute(viewModel, {}, { loginCalls++ }, {}) } }
  }

  private fun fill() {
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.Email))
        .performTextInput("student.test@epfl.ch")
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performTextInput("password1")
  }

  @Test
  fun missingNameKeepsTheHintAndRequiredErrorUntilEdited() {
    launch()
    compose.onNodeWithText("Your full name").assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performClick()
    compose
        .onNodeWithTag(SignUpTags.error(SignUpField.FullName))
        .assertTextContains("This field is required.")
    compose.onNodeWithText("Your full name").assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose.onNodeWithText("Your full name").assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.error(SignUpField.FullName)).assertDoesNotExist()
    assertEquals(0, auth.signUpCalls)
  }

  @Test
  fun duplicateRecoverySharesTheHelperLineAndKeepsAnAccessibleTouchTarget() {
    auth.signUpResult = SignUpResult.AlreadyInUse
    launch()
    fill()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    val recovery = compose.onNodeWithTag(SignUpTags.LogInInstead).performScrollTo()
    recovery.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    val helper = compose.onNodeWithTag(SignUpTags.BackendError)
    helper.assertTextContains("This email already has an account.")
    val errorBounds = helper.fetchSemanticsNode().boundsInRoot
    val linkBounds = recovery.fetchSemanticsNode().boundsInRoot
    assertTrue(errorBounds.right <= linkBounds.left)
    assertTrue(errorBounds.center.y in linkBounds.top..linkBounds.bottom)
    recovery.performClick()
    assertEquals(1, loginCalls)
    assertEquals(1, auth.signUpCalls)
  }

  @Test
  fun backendDomainRejectionAppearsByEmailWithoutDuplicateRecovery() {
    auth.signUpResult = SignUpResult.InvalidDomain
    launch()
    fill()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.BackendError)
        .performScrollTo()
        .assertTextContains("Use your @epfl.ch address.")
    compose.onNodeWithTag(SignUpTags.LogInInstead).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsEnabled()
    assertEquals(1, auth.signUpCalls)
  }

  @Test
  fun invalidPasswordShowsTheFieldErrorAndBlocksSubmission() {
    launch()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("short")
    compose
        .onNodeWithTag(SignUpTags.error(SignUpField.Password))
        .performScrollTo()
        .assertTextContains("Use at least 8 characters and include a number.")
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsNotEnabled()
    assertEquals(0, auth.signUpCalls)
  }
}
