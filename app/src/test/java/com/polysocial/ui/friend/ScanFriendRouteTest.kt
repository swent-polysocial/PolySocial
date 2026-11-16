// Contributors: Claude Opus 5.5 (wrote these tests, #359); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.ui.friend

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The route's camera permission: asked only when the scanner opens, and its answer followed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class ScanFriendRouteTest {
  @get:Rule(order = 0) val main = MainDispatcherRule(UnconfinedTestDispatcher())
  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

  private val requested = mutableListOf<Any?>()

  private val scanner =
      object : QrScanner {
        @Composable
        override fun Camera(
            scanning: Boolean,
            torchOn: Boolean,
            onCode: (String) -> Unit,
            modifier: Modifier,
        ) = Box(modifier)
      }

  /** A permission dialog that answers [granted] at once. */
  private fun answering(granted: Boolean) =
      object : ActivityResultRegistryOwner {
        override val activityResultRegistry =
            object : ActivityResultRegistry() {
              override fun <I, O> onLaunch(
                  requestCode: Int,
                  contract: ActivityResultContract<I, O>,
                  input: I,
                  options: ActivityOptionsCompat?,
              ) {
                requested += input
                dispatchResult(requestCode, granted)
              }
            }
      }

  private fun show(dialog: ActivityResultRegistryOwner) {
    val viewModel =
        ScanFriendViewModel(
            FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true)),
            FakePublicProfileRepository(),
            FakeFriendRepository(currentUid = "alice"),
        )
    compose.setContent {
      PolySocialTheme {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides dialog) {
          ScanFriendRoute(onBack = {}, viewModel = viewModel, scanner = scanner)
        }
      }
    }
  }

  @Test
  fun aGrantedRequest_startsTheCamera() {
    show(answering(granted = true))

    compose.onNodeWithTag(C.Tag.scan_friend_camera).assertIsDisplayed()
    assertEquals(listOf(Manifest.permission.CAMERA), requested)
  }

  @Test
  fun aRefusedRequest_showsCameraAccessIsOff() {
    show(answering(granted = false))

    compose.onNodeWithTag(C.Tag.scan_friend_camera_off).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_friend_camera).assertDoesNotExist()
    assertEquals(listOf(Manifest.permission.CAMERA), requested)
  }

  @Test
  fun anAlreadyGrantedCamera_isNotAskedAgain() {
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.CAMERA)

    show(answering(granted = false))

    compose.onNodeWithTag(C.Tag.scan_friend_camera).assertIsDisplayed()
    assertEquals(emptyList<Any?>(), requested)
  }
}
