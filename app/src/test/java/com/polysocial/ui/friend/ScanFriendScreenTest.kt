// Contributors: Claude Opus 5.5 (wrote these tests, #359); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.ui.friend

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A camera stand-in: CI has no camera, so tests report codes through [onCode] by hand. */
private class FakeQrScanner : QrScanner {
  var scanning: Boolean? = null
  var torchOn: Boolean? = null
  var onCode: ((String) -> Unit)? = null

  @Composable
  override fun Camera(
      scanning: Boolean,
      torchOn: Boolean,
      onCode: (String) -> Unit,
      modifier: Modifier,
  ) {
    this.scanning = scanning
    this.torchOn = torchOn
    this.onCode = onCode
    // The screen tags the camera it shows (C.Tag.scan_friend_camera).
    Box(modifier)
  }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class ScanFriendScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val scanner = FakeQrScanner()
  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("sports", "cinema"))
  private val calls = mutableListOf<String>()

  private fun show(state: ScanFriendUiState, access: CameraAccess = CameraAccess.GRANTED) {
    compose.setContent {
      PolySocialTheme {
        ScanFriendScreen(
            state = state,
            access = access,
            scanner = scanner,
            onClose = { calls += "close" },
            onCode = { calls += "code:$it" },
            onScanAgain = { calls += "again" },
            onButton = { calls += "button" },
            onOpenSettings = { calls += "settings" },
        )
      }
    }
  }

  private fun card(
      button: FriendButton,
      busy: Boolean = false,
      error: FriendActionError? = null,
  ) = ScanFriendUiState.Card(lea, button, busy, error)

  @Test
  fun scanning_showsTheCameraWithFigmasTextAndPassesCodesOn() {
    show(ScanFriendUiState.Scanning)

    compose.onNodeWithTag(C.Tag.scan_friend_camera).assertIsDisplayed()
    compose.onNodeWithText("Scan a friend’s QR code").assertIsDisplayed()
    compose.onNodeWithText("Fit their PolySocial friend code inside the frame.").assertIsDisplayed()
    assertEquals(true, scanner.scanning)
    compose.runOnIdle { scanner.onCode!!("polysocial://friend/lea") }
    assertEquals(listOf("code:polysocial://friend/lea"), calls)
  }

  @Test
  fun theCameraStopsScanningWhileAProfileLoadsOrAProblemShows() {
    var state by mutableStateOf<ScanFriendUiState>(ScanFriendUiState.Loading)
    compose.setContent {
      PolySocialTheme { ScanFriendScreen(state, CameraAccess.GRANTED, scanner, {}, {}, {}, {}, {}) }
    }
    compose.onNodeWithTag(C.Tag.scan_friend_loading).assertIsDisplayed()
    assertEquals(false, scanner.scanning)

    state = ScanFriendUiState.Problem(ScanProblem.INVALID_CODE)
    compose.waitForIdle()
    assertEquals(false, scanner.scanning)

    state = ScanFriendUiState.Scanning
    compose.waitForIdle()
    assertEquals(true, scanner.scanning)
  }

  @Test
  fun theTorchTurnsOnAndOff() {
    show(ScanFriendUiState.Scanning)
    assertEquals(false, scanner.torchOn)

    compose.onNodeWithTag(C.Tag.scan_friend_torch).performClick()
    compose.waitForIdle()
    assertEquals(true, scanner.torchOn)

    compose.onNodeWithTag(C.Tag.scan_friend_torch).performClick()
    compose.waitForIdle()
    assertEquals(false, scanner.torchOn)
  }

  @Test
  fun whileThePermissionIsAsked_theCameraIsNotStarted() {
    show(ScanFriendUiState.Scanning, CameraAccess.ASKING)

    compose.onNodeWithTag(C.Tag.scan_friend_camera).assertDoesNotExist()
    compose.onNodeWithText("Scan a friend’s QR code").assertIsDisplayed()
  }

  @Test
  fun aDeniedCamera_showsCameraAccessIsOffWithItsWaysOut() {
    show(ScanFriendUiState.Scanning, CameraAccess.DENIED)

    compose.onNodeWithTag(C.Tag.scan_friend_camera_off).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_friend_camera).assertDoesNotExist()
    compose.onNodeWithText("Camera access is off").assertIsDisplayed()
    compose
        .onNodeWithText("Allow camera access in Settings to scan a friend’s code.")
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_friend_open_settings).performClick()
    compose.onNodeWithTag(C.Tag.scan_friend_back_to_my_code).performClick()
    assertEquals(listOf("settings", "close"), calls)
  }

  @Test
  fun eachProblem_showsItsSheetWithScanAgainAndBackToMyCode() {
    val titles =
        mapOf(
            ScanProblem.INVALID_CODE to "Can’t use this code",
            ScanProblem.OWN_CODE to "That’s your own code",
            ScanProblem.NOT_FOUND to "Can’t find this student",
            ScanProblem.COULD_NOT_LOAD to "Couldn’t load this profile",
        )
    var state by mutableStateOf<ScanFriendUiState>(ScanFriendUiState.Scanning)
    compose.setContent {
      PolySocialTheme {
        ScanFriendScreen(
            state,
            CameraAccess.GRANTED,
            scanner,
            { calls += "close" },
            {},
            { calls += "again" },
            {},
            {},
        )
      }
    }
    for ((problem, title) in titles) {
      state = ScanFriendUiState.Problem(problem)
      compose.onNodeWithTag(C.Tag.scan_friend_problem_title).assertTextEquals(title)
    }
    compose.onNodeWithTag(C.Tag.scan_friend_scan_again).performClick()
    compose.onNodeWithTag(C.Tag.scan_friend_back_to_my_code).performClick()
    assertEquals(listOf("again", "close"), calls)
  }

  @Test
  fun scanning_hasNoSheet() {
    show(ScanFriendUiState.Scanning)

    compose.onNodeWithTag(C.Tag.scan_friend_problem).assertDoesNotExist()
  }

  @Test
  fun aCard_showsThePublicProfile() {
    show(card(FriendButton.ADD))

    compose.onNodeWithTag(C.Tag.scan_friend_card).assertIsDisplayed()
    compose.onNodeWithText("Public profile").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_friend_name).assertTextEquals("Léa Martin")
    compose.onNodeWithTag(C.Tag.scan_friend_details).assertTextEquals("IN · BA3")
    compose.onNodeWithText("LM").assertIsDisplayed()
    compose.onAllNodesWithTag(C.Tag.scan_friend_interest).assertCountEquals(2)
    compose.onNodeWithText("Sports").assertIsDisplayed()
    compose.onNodeWithText("Cinema").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_friend_camera).assertDoesNotExist()
  }

  @Test
  fun aCardWithoutInterests_hasNoInterestsTitle() {
    show(ScanFriendUiState.Card(lea.copy(interests = emptySet()), FriendButton.ADD))

    compose.onNodeWithText("Interests").assertDoesNotExist()
  }

  @Test
  fun addFriend_isEnabledWithoutANoteAndTapping_callsTheButton() {
    show(card(FriendButton.ADD))

    compose.onNodeWithTag(C.Tag.scan_friend_note).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.scan_friend_button).assertIsEnabled().performClick()
    assertEquals(listOf("button"), calls)
  }

  @Test
  fun eachFriendship_showsItsLabelNoteAndWhetherItCanBeTapped() {
    val expected =
        listOf(
            Triple(FriendButton.SENT, "Request sent", "Léa will see your request in PolySocial."),
            Triple(FriendButton.ACCEPT, "Accept request", "Léa sent you a friend request."),
            Triple(FriendButton.FRIENDS, "Friends", "You and Léa are friends."),
        )
    var state by mutableStateOf<ScanFriendUiState>(card(FriendButton.ADD))
    compose.setContent {
      PolySocialTheme { ScanFriendScreen(state, CameraAccess.GRANTED, scanner, {}, {}, {}, {}, {}) }
    }
    for ((button, label, note) in expected) {
      state = card(button)
      compose.onNodeWithTag(C.Tag.scan_friend_note).assertTextEquals(note)
      compose.onNodeWithText(label).assertIsDisplayed()
      if (button == FriendButton.ACCEPT) {
        compose.onNodeWithTag(C.Tag.scan_friend_button).assertIsEnabled()
      } else {
        compose.onNodeWithTag(C.Tag.scan_friend_button).assertIsNotEnabled()
      }
    }
  }

  @Test
  fun aFailedTap_saysWhyAndKeepsTheButton() {
    var state by
        mutableStateOf<ScanFriendUiState>(card(FriendButton.ADD, error = FriendActionError.OFFLINE))
    compose.setContent {
      PolySocialTheme { ScanFriendScreen(state, CameraAccess.GRANTED, scanner, {}, {}, {}, {}, {}) }
    }
    compose
        .onNodeWithTag(C.Tag.scan_friend_note)
        .assertTextEquals("You’re offline. Connect, then try again.")
    compose.onNodeWithTag(C.Tag.scan_friend_button).assertIsEnabled()

    state = card(FriendButton.ACCEPT, error = FriendActionError.FAILED)
    compose
        .onNodeWithTag(C.Tag.scan_friend_note)
        .assertTextEquals("Something went wrong. Try again.")
  }

  @Test
  fun aBusyButton_cannotBeTappedAgain() {
    show(card(FriendButton.ADD, busy = true))

    compose.onNodeWithTag(C.Tag.scan_friend_button).assertIsNotEnabled().performClick()
    assertEquals(emptyList<String>(), calls)
  }

  @Test
  fun closeFromTheCameraOrTheCard_callsClose() {
    var state by mutableStateOf<ScanFriendUiState>(ScanFriendUiState.Scanning)
    compose.setContent {
      PolySocialTheme {
        ScanFriendScreen(state, CameraAccess.GRANTED, scanner, { calls += "close" }, {}, {}, {}, {})
      }
    }
    compose.onNodeWithTag(C.Tag.scan_friend_close).performClick()
    state = card(FriendButton.ADD)
    compose.onNodeWithTag(C.Tag.scan_friend_close).performClick()
    assertEquals(listOf("close", "close"), calls)
  }
}
