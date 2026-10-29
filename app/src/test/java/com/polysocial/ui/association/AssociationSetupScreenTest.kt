// Contributors: OpenAI Codex (association setup UI actions, validation and retry tests, #125).
package com.polysocial.ui.association

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.CreateAssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssociationSetupScreenTest {
  @get:Rule val compose = createComposeRule()

  private val filled =
      AssociationSetupForm(
          "Example Club",
          AssociationType.CLUB,
          "A fictional club.",
          "https://example.org",
      )
  private val user = AuthUser("association-test", "club@example.org", true)
  private val auth = FakeAuthRepository(user)
  private val profiles = FakeUserProfileRepository()
  private val associations = FakeAssociationRepository()

  private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  private fun field(field: AssociationSetupField) = node(AssociationSetupTags.field(field))

  private fun string(id: Int) =
      ApplicationProvider.getApplicationContext<Application>().getString(id)

  @Test
  fun emptyFormShowsTheSetupAndNoPrematureValidationErrors() {
    compose.setContent {
      PolySocialTheme { AssociationSetupScreen(AssociationSetupUiState(), { _, _ -> }, {}, {}, {}) }
    }
    node(AssociationSetupTags.Screen).assertExists()
    compose.onNodeWithText(string(R.string.association_setup_title)).assertIsDisplayed()
    for (field in AssociationSetupField.entries) {
      node("${AssociationSetupTags.field(field)}_error").assertDoesNotExist()
    }
    node(AssociationSetupTags.Error).assertDoesNotExist()
    node(AssociationSetupTags.Submit).performScrollTo().assertIsEnabled()
  }

  @Test
  fun textInputsAndTypeChoicesForwardActionsAndUpdateInitials() {
    var state by mutableStateOf(AssociationSetupUiState())
    val edits = mutableListOf<Pair<AssociationSetupField, String>>()
    val selected = mutableListOf<AssociationType>()
    var submissions = 0
    var backs = 0
    compose.setContent {
      PolySocialTheme {
        AssociationSetupScreen(
            state,
            { field, value ->
              edits += field to value
              state =
                  state.copy(
                      form =
                          when (field) {
                            AssociationSetupField.Name -> state.form.copy(name = value)
                            AssociationSetupField.About -> state.form.copy(about = value)
                            AssociationSetupField.Website -> state.form.copy(website = value)
                            AssociationSetupField.Type -> state.form
                          }
                  )
            },
            {
              selected += it
              state = state.copy(form = state.form.copy(type = it))
            },
            { submissions++ },
            { backs++ },
        )
      }
    }
    field(AssociationSetupField.Name).performScrollTo().performTextInput("Example Club")
    node(AssociationSetupTags.Initials).assertTextEquals("EC")
    for (type in AssociationType.entries) {
      node(AssociationSetupTags.type(type)).performScrollTo().performClick().assertIsSelected()
      compose.onNodeWithText(string(type.label())).assertExists()
    }
    field(AssociationSetupField.About).performScrollTo().performTextInput("A fictional club.")
    field(AssociationSetupField.Website).performScrollTo().performTextInput("https://example.org")
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    node(AssociationSetupTags.Back).performScrollTo().performClick()
    assertEquals(AssociationType.entries, selected)
    assertEquals(
        listOf(
            AssociationSetupField.Name,
            AssociationSetupField.About,
            AssociationSetupField.Website,
        ),
        edits.map { it.first },
    )
    assertEquals(1, submissions)
    assertEquals(1, backs)
  }

  @Test
  fun requiredErrorsAppearOnlyForTouchedFields() {
    compose.setContent {
      PolySocialTheme {
        AssociationSetupScreen(
            AssociationSetupUiState(touched = AssociationSetupField.entries.toSet()),
            { _, _ -> },
            {},
            {},
            {},
        )
      }
    }
    for (field in
        listOf(
            AssociationSetupField.Name,
            AssociationSetupField.Type,
            AssociationSetupField.About,
        )) {
      node("${AssociationSetupTags.field(field)}_error")
          .performScrollTo()
          .assertTextEquals(string(R.string.association_required))
    }
    node("${AssociationSetupTags.field(AssociationSetupField.Website)}_error").assertDoesNotExist()
  }

  @Test
  fun overlongAndInvalidWebsiteErrorsAreShownBesideTheirFields() {
    compose.setContent {
      PolySocialTheme {
        AssociationSetupScreen(
            AssociationSetupUiState(
                form =
                    filled.copy(
                        name = "a".repeat(61),
                        about = "a".repeat(1001),
                        website = "http://example.org",
                    ),
                touched = AssociationSetupField.entries.toSet(),
            ),
            { _, _ -> },
            {},
            {},
            {},
        )
      }
    }
    for (field in listOf(AssociationSetupField.Name, AssociationSetupField.About)) {
      node("${AssociationSetupTags.field(field)}_error")
          .performScrollTo()
          .assertTextEquals(string(R.string.association_too_long))
    }
    node("${AssociationSetupTags.field(AssociationSetupField.Website)}_error")
        .performScrollTo()
        .assertTextEquals(string(R.string.association_website_invalid))
  }

  @Test
  fun savingAndSavedStatesLockAllCompetingActions() {
    var state by mutableStateOf(AssociationSetupUiState(form = filled, saving = true))
    var actions = 0
    compose.setContent {
      PolySocialTheme {
        AssociationSetupScreen(
            state,
            { _, _ -> actions++ },
            { actions++ },
            { actions++ },
            { actions++ },
        )
      }
    }
    for (saved in listOf(false, true)) {
      compose.runOnIdle { state = state.copy(saving = !saved, saved = saved) }
      for (input in
          listOf(
              AssociationSetupField.Name,
              AssociationSetupField.About,
              AssociationSetupField.Website,
          )) {
        field(input).performScrollTo().assertIsNotEnabled()
      }
      for (type in AssociationType.entries) node(AssociationSetupTags.type(type))
          .performScrollTo()
          .assertIsNotEnabled()
      node(AssociationSetupTags.Submit).performScrollTo().assertIsNotEnabled().performClick()
      compose
          .onNodeWithText(
              string(if (saved) R.string.association_submit else R.string.association_saving)
          )
          .assertExists()
      node(AssociationSetupTags.Back).performScrollTo().assertIsNotEnabled().performClick()
    }
    assertEquals(0, actions)
  }

  @Test
  fun backendErrorsExplainNetworkFailuresAndKeepRetryAvailable() {
    var state by mutableStateOf(AssociationSetupUiState(form = filled))
    compose.setContent {
      PolySocialTheme { AssociationSetupScreen(state, { _, _ -> }, {}, {}, {}) }
    }
    for (failure in AssociationSetupFailure.entries) {
      compose.runOnIdle { state = state.copy(error = failure) }
      node(AssociationSetupTags.Error)
          .performScrollTo()
          .assertTextEquals(
              string(
                  if (failure == AssociationSetupFailure.Network) R.string.association_setup_network
                  else R.string.association_setup_error
              )
          )
      node(AssociationSetupTags.Submit).performScrollTo().assertIsEnabled()
      assertEquals(filled, state.form)
    }
  }

  @Test
  fun routeSubmitsTheFormAndReportsCompletionAfterTheRepositorySucceeds() {
    val viewModel = AssociationSetupViewModel(auth, profiles, associations, SavedStateHandle())
    var savedCalls = 0
    var backs = 0
    compose.setContent {
      PolySocialTheme { AssociationSetupRoute({ backs++ }, { savedCalls++ }, viewModel) }
    }
    field(AssociationSetupField.Name).performScrollTo().performTextInput("Example Club")
    node(AssociationSetupTags.type(AssociationType.CLUB)).performScrollTo().performClick()
    field(AssociationSetupField.About).performScrollTo().performTextInput("A fictional club.")
    field(AssociationSetupField.Website).performScrollTo().performTextInput("https://example.org")
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(1, savedCalls)
    assertEquals(0, backs)
    assertEquals(AccountType.ASSOCIATION, profiles.createdProfiles.single().accountType)
    assertEquals("Example Club", associations.createdAssociations.single().name)
    assertEquals(AssociationType.CLUB, associations.createdAssociations.single().type)
    assertEquals("https://example.org", associations.createdAssociations.single().website)
  }

  @Test
  fun routePreservesTheFormAfterOfflineFailureAndCompletesOnRetry() {
    associations.createAssociationResult = CreateAssociationResult.NetworkError
    val viewModel = AssociationSetupViewModel(auth, profiles, associations, SavedStateHandle())
    viewModel.update(AssociationSetupField.Name, filled.name)
    viewModel.selectType(AssociationType.CLUB)
    viewModel.update(AssociationSetupField.About, filled.about)
    var savedCalls = 0
    compose.setContent {
      PolySocialTheme { AssociationSetupRoute({}, { savedCalls++ }, viewModel) }
    }
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.waitForIdle()
    node(AssociationSetupTags.Error)
        .performScrollTo()
        .assertTextEquals(string(R.string.association_setup_network))
    assertEquals(filled.name, viewModel.uiState.value.form.name)
    assertEquals(0, savedCalls)
    compose.runOnIdle {
      associations.createAssociationResult = CreateAssociationResult.Created
      profiles.getProfileResult =
          ProfileResult.Found(
              UserProfile(user.uid, user.email, filled.name, "", "", AccountType.ASSOCIATION)
          )
    }
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(1, savedCalls)
    assertEquals(2, associations.createdAssociations.size)
  }

  @Test
  fun routeLocksBackWhileSavingAndUsesTheCurrentSavedCallback() {
    associations.gate = CompletableDeferred()
    val viewModel = AssociationSetupViewModel(auth, profiles, associations, SavedStateHandle())
    viewModel.update(AssociationSetupField.Name, filled.name)
    viewModel.selectType(AssociationType.CLUB)
    viewModel.update(AssociationSetupField.About, filled.about)
    var useReplacement by mutableStateOf(false)
    var previous = 0
    var replacement = 0
    compose.setContent {
      PolySocialTheme {
        AssociationSetupRoute(
            {},
            if (useReplacement) ({ replacement++ }) else ({ previous++ }),
            viewModel,
        )
      }
    }
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.waitForIdle()
    node(AssociationSetupTags.Back).performScrollTo().assertIsNotEnabled()
    compose.runOnIdle { useReplacement = true }
    compose.runOnIdle { associations.gate!!.complete(Unit) }
    compose.waitForIdle()
    assertEquals(0, previous)
    assertEquals(1, replacement)
    assertTrue(viewModel.uiState.value.saved)
  }

  @Test
  fun retainedSavedModelDoesNotExposeAnotherAccountsFormOrCompleteIt() {
    val vm = AssociationSetupViewModel(auth, profiles, associations, SavedStateHandle())
    vm.update(AssociationSetupField.Name, filled.name)
    vm.selectType(AssociationType.CLUB)
    vm.update(AssociationSetupField.About, filled.about)
    var visible by mutableStateOf(true)
    var completed = 0
    compose.setContent {
      PolySocialTheme { if (visible) AssociationSetupRoute({}, { completed++ }, vm) }
    }
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(1, completed)
    compose.runOnIdle { visible = false }
    compose.runOnIdle {
      auth.user = user.copy(uid = "next-association", email = "next@example.org")
      visible = true
    }
    node(AssociationSetupTags.Screen).assertIsDisplayed()
    assertEquals(AssociationSetupForm(), vm.uiState.value.form)
    assertEquals(1, completed)
    assertEquals(false, vm.uiState.value.saved)
  }

  @Test
  fun replacingAnInFlightModelRebindsEditsAndCompletion() {
    val gate = CompletableDeferred<Unit>()
    associations.gate = gate
    val old = AssociationSetupViewModel(auth, profiles, associations, SavedStateHandle())
    old.update(AssociationSetupField.Name, filled.name)
    old.selectType(AssociationType.CLUB)
    old.update(AssociationSetupField.About, filled.about)
    val nextAssociations = FakeAssociationRepository()
    val next =
        AssociationSetupViewModel(
            auth,
            FakeUserProfileRepository(),
            nextAssociations,
            SavedStateHandle(),
        )
    var current by mutableStateOf(old)
    var completed = 0
    compose.setContent { PolySocialTheme { AssociationSetupRoute({}, { completed++ }, current) } }
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.runOnIdle { current = next }
    field(AssociationSetupField.Name).performScrollTo().performTextInput("Next Club")
    node(AssociationSetupTags.type(AssociationType.CLUB)).performScrollTo().performClick()
    field(AssociationSetupField.About).performScrollTo().performTextInput("Next activities")
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals("Next Club", nextAssociations.createdAssociations.single().name)
    assertEquals(filled.name, old.uiState.value.form.name)
    assertEquals(1, completed)
    compose.runOnIdle { gate.complete(Unit) }
    compose.waitForIdle()
    assertEquals(1, completed)
  }

  @Test
  fun systemBackIsBlockedUntilTheSaveFailureCanBeRecovered() {
    val gate = CompletableDeferred<Unit>()
    associations.gate = gate
    associations.createAssociationResult = CreateAssociationResult.NetworkError
    val vm = AssociationSetupViewModel(auth, profiles, associations, SavedStateHandle())
    vm.update(AssociationSetupField.Name, filled.name)
    vm.selectType(AssociationType.CLUB)
    vm.update(AssociationSetupField.About, filled.about)
    var dispatcher: OnBackPressedDispatcher? = null
    var backs = 0
    compose.setContent {
      PolySocialTheme {
        dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        BackHandler { backs++ }
        AssociationSetupRoute({}, {}, vm)
      }
    }
    node(AssociationSetupTags.Submit).performScrollTo().performClick()
    compose.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
    assertEquals(0, backs)
    compose.runOnIdle { gate.complete(Unit) }
    node(AssociationSetupTags.Error).performScrollTo().assertExists()
    compose.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
    assertEquals(1, backs)
    assertEquals(filled.name, vm.uiState.value.form.name)
  }
}
