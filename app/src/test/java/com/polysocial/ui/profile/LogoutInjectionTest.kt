// Contributors: OpenAI Codex (keep association onboarding dependencies fake in Hilt tests, #125).
// Contributors: OpenAI Codex (Hilt logout and verification wiring with offline fakes, #109).
// Contributors: Claude (fake association repository for the association names, #128; fake
// registration repository for the manage event's count, #131).
// Contributors: Claude (Opus 5.5, Anthropic) bound a fake session guard and its parts,
// #198; Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (Opus 5.5, Anthropic) bound a fake account deletion, #207; Mohamed
// Khellaf (reviewed and revised); Claude explained why in a comment after #286's review.
// Contributors: Claude (Opus 5.5, Anthropic) bound fake group and public profile repositories,
// which Find a group's ViewModel needs, #233.
// Contributors: OpenAI Codex (scoped the chat fake to this Hilt test, #238).
package com.polysocial.ui.profile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.auth.MemoryVerificationStore
import com.polysocial.model.account.AccountDeletionModule
import com.polysocial.model.account.AccountDeletionRepository
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.association.AssociationModule
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.*
import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.chat.FakeMessageRepository
import com.polysocial.model.chat.MessageModule
import com.polysocial.model.chat.MessageRepository
import com.polysocial.model.event.*
import com.polysocial.model.firebase.*
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupModule
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.RegistrationModule
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.user.*
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfileModule
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.auth.WelcomeTags
import dagger.hilt.android.testing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises default routes and injected ViewModels, rather than replacing app screens. */
@HiltAndroidTest
@UninstallModules(
    AccountDeletionModule::class,
    AssociationModule::class,
    AuthModule::class,
    EventModule::class,
    UserProfileModule::class,
    FirebaseModule::class,
    MessageModule::class,
    RegistrationModule::class,
    GroupModule::class,
    PublicProfileModule::class,
)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class LogoutInjectionTest {
  @BindValue @JvmField val messages: MessageRepository = FakeMessageRepository()
  @BindValue
  @JvmField
  val associationSignupStore: AssociationSignupStore = EmptyAssociationSignupStore

  private val student = AuthUser("fictional-student", "fictional@epfl.ch", true)
  private val fakeAuth = FakeAuthRepository(student)
  private var cleanup = CacheClearResult.UnexpectedError
  private var clears = 0
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
  @BindValue @JvmField val sessionGuard: SessionGuard = FakeSessionGuard()
  // PolySocialApp injects the real guard, which needs these too, though nothing starts it here.
  @BindValue @JvmField val sessionSource: AuthSessionSource = FakeAuthSessionSource()
  @BindValue @JvmField val sessionOwner: SessionOwnerStore = FakeSessionOwnerStore()
  @BindValue @JvmField val verificationClock: VerificationClock = VerificationClock { 1000 }
  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val events: EventRepository = FakeEventRepository()
  @BindValue @JvmField val registrations: RegistrationRepository = FakeRegistrationRepository()
  @BindValue @JvmField val groups: GroupRepository = FakeGroupRepository()
  @BindValue @JvmField val publicProfiles: PublicProfileRepository = FakePublicProfileRepository()
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()
  // Hilt checks at build time that every @HiltViewModel's dependencies are bound, including
  // DeleteAccountViewModel's, and the real deletion repository needs the uninstalled Firestore.
  @BindValue @JvmField val deletion: AccountDeletionRepository = FakeAccountDeletionRepository()
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
          return cleanup
        }
      }
  @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun productionRoutesUseInjectedCleanupAndAuthThenExitAfterRetry() {
    compose.onNodeWithTag(C.Tag.screen_events).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.nav_item_profile).performClick()
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Cancel).performClick()
    assertEquals(student, fakeAuth.user)
    assertEquals(0, clears)
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(ProfileTags.Error).assertIsDisplayed()
    assertEquals(student, fakeAuth.user)
    assertEquals(0, fakeAuth.logOutCalls)
    cleanup = CacheClearResult.Cleared
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertNull(fakeAuth.user)
    assertEquals(1, fakeAuth.logOutCalls)
    assertEquals(2, clears)
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertTrue(compose.activity.isFinishing)
  }
}
