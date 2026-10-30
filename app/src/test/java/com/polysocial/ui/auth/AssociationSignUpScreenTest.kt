// Contributors: OpenAI Codex (association signup hints and short-window review regressions, #125).
package com.polysocial.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.SignUpField
import com.polysocial.model.auth.SignUpForm
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.user.AccountType
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class AssociationSignUpScreenTest {
  @get:Rule val compose = createComposeRule()
  private val state = SignUpUiState(form = SignUpForm(accountType = AccountType.ASSOCIATION))

  @Test
  fun associationUsesOnePasswordHintWithoutTheStudentChecklistOrGoogle() {
    var hint = ""
    compose.setContent {
      hint = stringResource(R.string.association_signup_password_hint)
      PolySocialTheme { SignUpScreen(state, { _, _ -> }, {}, {}, {}, {}) }
    }
    compose.onNodeWithText(hint).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.LengthRule).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.NumberRule).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Google).assertDoesNotExist()
    val screen = compose.onNodeWithTag(SignUpTags.Screen).fetchSemanticsNode().boundsInRoot
    val submit =
        compose
            .onNodeWithTag(SignUpTags.Submit)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
    assertTrue(
        "Create account stays in the bottom part of a tall viewport",
        submit.top > screen.top + screen.height * 0.7f,
    )
    compose.onNodeWithTag(SignUpTags.LogIn).assertIsDisplayed()
  }

  @Test
  fun shortWindowKeepsTheBottomActionsReachableByScrolling() {
    var logins = 0
    compose.setContent {
      PolySocialTheme {
        Box(Modifier.height(360.dp)) { SignUpScreen(state, { _, _ -> }, {}, {}, { logins++ }, {}) }
      }
    }
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, logins)
  }

  @Test
  fun associationShowsRecoverableBackendErrorWithoutLeavingTheForm() {
    var message = ""
    val errorState = state.copy(status = SignUpStatus.Error(SignUpResult.NetworkError))
    compose.setContent {
      message = stringResource(R.string.signup_network_error)
      PolySocialTheme { SignUpScreen(errorState, { _, _ -> }, {}, {}, {}, {}) }
    }

    compose.onNodeWithTag(SignUpTags.BackendError).assertIsDisplayed()
    compose.onNodeWithText(message).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Submit).assertIsDisplayed()
  }

  @Test
  fun leavingAnAssociationFieldRunsItsBlurValidation() {
    var blurred = 0
    compose.setContent {
      PolySocialTheme {
        SignUpScreen(
            state,
            { _, _ -> },
            { blurred++ },
            {},
            {},
            {},
        )
      }
    }

    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performClick()

    assertEquals(1, blurred)
  }

  @Test
  fun associationDuplicateEmailOffersTheLoginRecoveryPath() {
    var logins = 0
    val errorState = state.copy(status = SignUpStatus.Error(SignUpResult.AlreadyInUse))
    compose.setContent {
      PolySocialTheme { SignUpScreen(errorState, { _, _ -> }, {}, {}, { logins++ }, {}) }
    }

    compose.onNodeWithTag(SignUpTags.LogInInstead).performScrollTo().performClick()

    assertEquals(1, logins)
  }

  @Test
  fun associationShowsLoadingStateWhileCreatingTheAccount() {
    val loadingState = state.copy(status = SignUpStatus.Loading)
    var label = ""
    compose.setContent {
      label = stringResource(R.string.signup_creating)
      PolySocialTheme { SignUpScreen(loadingState, { _, _ -> }, {}, {}, {}, {}) }
    }

    compose.onNodeWithTag(SignUpTags.LoadingSpinner).assertIsDisplayed()
    compose.onNodeWithText(label).assertIsDisplayed()
  }

  @Test
  fun studentPasswordRulesShowSatisfiedStateWhenRequirementsAreMet() {
    val studentState =
        SignUpUiState(
            form =
                SignUpForm(
                    fullName = "Test Student",
                    email = "student@epfl.ch",
                    password = "password1",
                    confirmPassword = "password1",
                )
        )
    compose.setContent {
      PolySocialTheme { SignUpScreen(studentState, { _, _ -> }, {}, {}, {}, {}) }
    }

    compose.onNodeWithTag(SignUpTags.LengthRule).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.NumberRule).assertIsDisplayed()
  }
}
