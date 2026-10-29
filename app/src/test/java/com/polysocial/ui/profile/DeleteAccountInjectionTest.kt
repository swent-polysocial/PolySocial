// Contributors: Claude (Opus 5.5, Anthropic) drafted the account deletion flow through the real
// routes and the lost-session case, #207; Mohamed Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.R
import com.polysocial.auth.MemoryVerificationStore
import com.polysocial.model.account.AccountDeletionModule
import com.polysocial.model.account.AccountDeletionRepository
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.association.AssociationModule
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthModule
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.ReauthResult
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.FirebaseModule
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.network.NetworkModule
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileModule
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.auth.WelcomeTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Deletes the account through the production routes and injected ViewModels (#207): Profile →
 * Privacy & data → Delete account, then Welcome with nothing to go back to.
 */
@HiltAndroidTest
@UninstallModules(
    AccountDeletionModule::class,
    AssociationModule::class,
    AuthModule::class,
    EventModule::class,
    FirebaseModule::class,
    NetworkModule::class,
    UserProfileModule::class,
)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class DeleteAccountInjectionTest {
  private val student = AuthUser("fictional-student", "fictional@epfl.ch", true)
  private val fakeAuth = FakeAuthRepository(student)
  private val fakeDeletion = FakeAccountDeletionRepository()
  private val memory =
      MemoryVerificationStore().apply { timings[student.uid] = VerificationTiming(1, 2) }
  private var clears = 0
  @BindValue @JvmField val verificationStore: VerificationStore = memory
  @BindValue @JvmField val verificationClock: VerificationClock = VerificationClock { 1000 }
  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val deletion: AccountDeletionRepository = fakeDeletion
  @BindValue @JvmField val events: EventRepository = FakeEventRepository()
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()
  @BindValue
  @JvmField
  val network: NetworkMonitor =
      object : NetworkMonitor {
        override fun isOnline() = true
      }
  @BindValue
  @JvmField
  val profiles: UserProfileRepository =
      FakeUserProfileRepository(
          ProfileResult.Found(UserProfile(student.uid, student.email, "Test Student", "IN", "BA1"))
      )
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
  fun deletingFromPrivacyOpensWelcomeAndBackExits() {
    compose.onNodeWithTag(C.Tag.screen_events).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.nav_item_profile).performClick()
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performClick()
    compose.onNodeWithTag(C.Tag.privacy_delete).performScrollTo().performClick()
    compose.onNodeWithTag(DeleteAccountTags.Confirm).performScrollTo().performClick()
    compose.onNodeWithTag(DeleteAccountTags.Password).performTextInput("fictional-password")
    compose.onNodeWithTag(DeleteAccountTags.Continue).performScrollTo().performClick()

    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose
        .onNodeWithText(compose.activity.getString(R.string.delete_account_done))
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(1, fakeDeletion.calls)
    assertEquals(1, clears)
    assertEquals(1, fakeAuth.logOutCalls)
    assertNull(fakeAuth.user)
    assertEquals(VerificationTiming(), runBlocking { memory.read(student.uid) })
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertTrue(compose.activity.isFinishing)
  }

  @Test
  fun lostSessionOpensWelcomeWithoutSayingTheAccountWasDeleted() {
    fakeAuth.reauthResult = ReauthResult.NotSignedIn
    compose.onNodeWithTag(C.Tag.nav_item_profile).performClick()
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performClick()
    compose.onNodeWithTag(C.Tag.privacy_delete).performScrollTo().performClick()
    compose.onNodeWithTag(DeleteAccountTags.Confirm).performScrollTo().performClick()
    compose.onNodeWithTag(DeleteAccountTags.Password).performTextInput("fictional-password")
    compose.onNodeWithTag(DeleteAccountTags.Continue).performScrollTo().performClick()

    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose
        .onNodeWithText(compose.activity.getString(R.string.delete_account_done))
        .assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(0, fakeDeletion.calls)
    assertEquals(1, clears)
    assertEquals(1, fakeAuth.logOutCalls)
  }
}
