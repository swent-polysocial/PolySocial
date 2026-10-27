// Contributors: OpenAI Codex (Hilt logout and verification wiring with offline fakes, #109).
// Contributors: Claude (fake association repository for Create Event's organizer row, #134).
package com.polysocial.ui.profile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.auth.MemoryVerificationStore
import com.polysocial.model.association.AssociationModule
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.*
import com.polysocial.model.event.*
import com.polysocial.model.firebase.*
import com.polysocial.model.user.*
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
    AssociationModule::class,
    AuthModule::class,
    EventModule::class,
    UserProfileModule::class,
    FirebaseModule::class,
)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class LogoutInjectionTest {
  private val student = AuthUser("fictional-student", "fictional@epfl.ch", true)
  private val fakeAuth = FakeAuthRepository(student)
  private var cleanup = CacheClearResult.UnexpectedError
  private var clears = 0
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
  @BindValue @JvmField val verificationClock: VerificationClock = VerificationClock { 1000 }
  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val events: EventRepository = FakeEventRepository()
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()
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
