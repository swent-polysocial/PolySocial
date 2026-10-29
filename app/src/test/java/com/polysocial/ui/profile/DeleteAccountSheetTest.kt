// Contributors: Claude (Opus 5.5, Anthropic) drafted the Delete account sheet tests, #207; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.auth.MemoryVerificationStore
import com.polysocial.model.account.AccountDeletionResult
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.ReauthResult
import com.polysocial.model.auth.SignInMethod
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.privacy.PrivacyContactLauncher
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Privacy's Delete account entry and every state of its sheet, with fakes (#207). */
@RunWith(AndroidJUnit4::class)
class DeleteAccountSheetTest {
  @get:Rule val rule = createComposeRule()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val student = AuthUser("fictional-uid", "fictional.student@epfl.ch", true)
  private val auth = FakeAuthRepository(student)
  private val deletion = FakeAccountDeletionRepository()
  private var online = true
  private var cacheResult = CacheClearResult.Cleared
  private var clears = 0
  private var deletedCalls = 0
  private var drafts = 0

  private val viewModel =
      DeleteAccountViewModel(
          auth,
          deletion,
          object : NetworkMonitor {
            override fun isOnline() = online
          },
          object : SessionCache {
            override suspend fun clear(): CacheClearResult {
              clears++
              return cacheResult
            }
          },
          MemoryVerificationStore(),
      )

  private fun text(id: Int) = context.getString(id)

  private fun open(contactEmail: String = "") {
    val privacy =
        PrivacyViewModel(
            contactEmail,
            PrivacyContactLauncher { _, _ ->
              drafts++
              true
            },
        )
    rule.setContent {
      PolySocialTheme {
        PrivacyRoute({}, {}, { deletedCalls++ }, viewModel = privacy, deleteViewModel = viewModel)
      }
    }
    rule.onNodeWithTag(C.Tag.privacy_delete).performScrollTo().performClick()
    rule.onNodeWithTag(DeleteAccountTags.Sheet).assertIsDisplayed()
  }

  private fun title(id: Int) =
      rule.onNodeWithTag(DeleteAccountTags.Title).assertTextEquals(text(id))

  private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

  private fun reauthWithPassword() {
    tap(DeleteAccountTags.Confirm)
    title(R.string.delete_account_reauth_title)
    rule.onNodeWithTag(DeleteAccountTags.Password).performTextInput("fictional-password")
    tap(DeleteAccountTags.Confirm)
  }

  @Test
  fun confirmationStatesTheConsequencesAndCancelChangesNothing() {
    open()
    title(R.string.delete_account_title)
    rule.onNodeWithText(text(R.string.delete_account_body)).assertIsDisplayed()
    rule.onNodeWithTag(DeleteAccountTags.Confirm).assertIsEnabled()
    tap(DeleteAccountTags.Cancel)
    rule.onNodeWithTag(DeleteAccountTags.Sheet).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    assertEquals(0, deletion.calls)
    assertEquals(0, auth.reauthCalls)
    assertEquals(student, auth.user)
  }

  @Test
  fun offlineKeepsTheConfirmationWithItsMessage() {
    online = false
    open()
    tap(DeleteAccountTags.Confirm)
    title(R.string.delete_account_title)
    rule
        .onNodeWithTag(DeleteAccountTags.Error)
        .assertTextEquals(text(R.string.delete_account_offline))
    assertEquals(0, auth.reauthCalls)
  }

  @Test
  fun passwordStepDeletesWhileLockedThenLeavesTheApp() {
    val gate = CompletableDeferred<Unit>()
    deletion.gate = gate
    open()
    tap(DeleteAccountTags.Confirm)
    rule.onNodeWithText(text(R.string.delete_account_reauth_password)).assertIsDisplayed()
    rule.onNodeWithTag(DeleteAccountTags.Google).assertDoesNotExist()
    rule.onNodeWithTag(DeleteAccountTags.Confirm).assertIsNotEnabled()
    rule.onNodeWithTag(DeleteAccountTags.Password).performTextInput("fictional-password")
    tap(DeleteAccountTags.Confirm)
    rule
        .onNodeWithTag(DeleteAccountTags.Progress)
        .assertTextEquals(text(R.string.delete_account_deleting))
    rule.onNodeWithTag(DeleteAccountTags.Cancel).assertIsNotEnabled()
    rule.onNodeWithTag(DeleteAccountTags.Confirm).assertDoesNotExist()
    assertEquals(0, deletedCalls)
    rule.runOnIdle { gate.complete(Unit) }
    rule.waitForIdle()
    assertEquals("fictional-password", auth.lastReauthPassword)
    assertEquals(1, deletion.calls)
    assertEquals(1, clears)
    assertNull(auth.user)
    assertEquals(1, deletedCalls)
  }

  @Test
  fun googleOnlyAccountConfirmsWithGoogle() {
    auth.signInMethods = setOf(SignInMethod.GOOGLE)
    auth.reauthResult = ReauthResult.Cancelled
    open()
    tap(DeleteAccountTags.Confirm)
    rule.onNodeWithText(text(R.string.delete_account_reauth_google)).assertIsDisplayed()
    rule.onNodeWithTag(DeleteAccountTags.Password).assertDoesNotExist()
    tap(DeleteAccountTags.Google)
    rule.waitForIdle()
    assertEquals(1, auth.googleReauthCalls)
    title(R.string.delete_account_reauth_title)
    assertEquals(0, deletion.calls)
  }

  @Test
  fun accountWithBothMethodsOffersBoth() {
    auth.signInMethods = setOf(SignInMethod.PASSWORD, SignInMethod.GOOGLE)
    open()
    tap(DeleteAccountTags.Confirm)
    rule.onNodeWithText(text(R.string.delete_account_reauth_both)).assertIsDisplayed()
    rule.onNodeWithTag(DeleteAccountTags.Password).assertIsDisplayed()
    rule.onNodeWithTag(DeleteAccountTags.Google).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun wrongPasswordAndExpiredSignInStayOnTheStepWithTheirMessage() {
    auth.reauthResult = ReauthResult.WrongPassword
    open()
    reauthWithPassword()
    title(R.string.delete_account_reauth_title)
    rule
        .onNodeWithTag(DeleteAccountTags.Error)
        .assertTextEquals(text(R.string.delete_account_wrong_password))
    auth.reauthResult = ReauthResult.Success
    deletion.result = AccountDeletionResult.RecentLoginRequired
    rule.onNodeWithTag(DeleteAccountTags.Password).performTextInput("fictional-password")
    tap(DeleteAccountTags.Confirm)
    title(R.string.delete_account_reauth_title)
    rule
        .onNodeWithTag(DeleteAccountTags.Error)
        .assertTextEquals(text(R.string.delete_account_sign_in_again))
    assertEquals(0, deletedCalls)
  }

  @Test
  fun networkErrorOffersRetryThatFinishesTheDeletion() {
    deletion.result = AccountDeletionResult.NetworkError
    open()
    reauthWithPassword()
    title(R.string.delete_account_title)
    rule
        .onNodeWithTag(DeleteAccountTags.Error)
        .assertTextEquals(text(R.string.delete_account_network))
    deletion.result = AccountDeletionResult.Deleted
    tap(DeleteAccountTags.Retry)
    rule.waitForIdle()
    assertEquals(2, deletion.calls)
    assertEquals(1, deletedCalls)
  }

  @Test
  fun associationIsPointedToThePrivacyContact() {
    deletion.result = AccountDeletionResult.NotAllowed
    open()
    reauthWithPassword()
    title(R.string.delete_account_association_title)
    rule.onNodeWithText(text(R.string.delete_account_association_body)).assertIsDisplayed()
    tap(DeleteAccountTags.Contact)
    rule
        .onNodeWithTag(DeleteAccountTags.ContactMessage)
        .assertTextEquals(text(R.string.privacy_contact_pending))
    tap(DeleteAccountTags.Cancel)
    rule.onNodeWithTag(DeleteAccountTags.Sheet).assertDoesNotExist()
    assertEquals(student, auth.user)
    // Reopened, the old contact feedback is gone.
    tap(C.Tag.privacy_delete)
    rule.onNodeWithTag(DeleteAccountTags.ContactMessage).assertDoesNotExist()
  }

  @Test
  fun configuredContactOpensTheDraftFromTheAssociationMessage() {
    deletion.result = AccountDeletionResult.NotAllowed
    open(contactEmail = "team@example.invalid")
    reauthWithPassword()
    tap(DeleteAccountTags.Contact)
    assertEquals(1, drafts)
    rule.onNodeWithTag(DeleteAccountTags.ContactMessage).assertDoesNotExist()
  }

  @Test
  fun lostSessionCleanupFailureDoesNotSayTheAccountIsDeleted() {
    cacheResult = CacheClearResult.UnexpectedError
    auth.reauthResult = ReauthResult.NotSignedIn
    open()
    reauthWithPassword()
    title(R.string.delete_account_cleanup_session_title)
    rule.onNodeWithText(text(R.string.delete_account_cleanup_title)).assertDoesNotExist()
    assertEquals(0, deletion.calls)
  }

  @Test
  fun failedCleanupBlocksWithRetryOnly() {
    cacheResult = CacheClearResult.UnexpectedError
    open()
    reauthWithPassword()
    title(R.string.delete_account_cleanup_title)
    rule
        .onNodeWithTag(DeleteAccountTags.Error)
        .assertTextEquals(text(R.string.delete_account_cleanup_error))
    rule.onNodeWithTag(DeleteAccountTags.Cancel).assertDoesNotExist()
    assertEquals(0, deletedCalls)
    cacheResult = CacheClearResult.Cleared
    tap(DeleteAccountTags.Retry)
    rule.waitForIdle()
    assertEquals(2, clears)
    assertEquals(1, deletedCalls)
  }
}
