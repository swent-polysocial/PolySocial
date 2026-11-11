// Contributors: Claude Opus 5.5 (drafted the My friend code screen tests, #339); Konstantinos
// Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.polysocial.R
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationType
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.qr.QrPayload
import com.polysocial.model.qr.encodeQrCode
import com.polysocial.model.qr.friendQrPayload
import com.polysocial.model.qr.parseQrPayload
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.association.OrganizationScreen
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class FriendCodeScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val uid = "fictionalStudentUid01"
  private val shown =
      FriendCodeUiState.Shown(encodeQrCode(friendQrPayload(uid)!!)!!, "Léa Martin", "IN · BA3")

  private fun text(id: Int) = compose.activity.getString(id)

  private fun show(state: FriendCodeUiState, onBack: () -> Unit = {}) {
    compose.setContent { PolySocialTheme { FriendCodeScreen(state, onBack) } }
  }

  @Test
  fun showsTheTitleTheStudentTheCodeAndWhatSharingItDoes() {
    show(shown)

    compose
        .onNodeWithTag(C.Tag.friend_code_heading)
        .assertTextEquals("My friend code")
        .assert(isHeading())
    compose.onNodeWithTag(C.Tag.friend_code_name).assertTextEquals("Léa Martin")
    compose.onNodeWithTag(C.Tag.friend_code_details).assertTextEquals("IN · BA3")
    compose.onNodeWithText("LM").assertIsDisplayed()
    compose
        .onNodeWithTag(C.Tag.friend_code_qr)
        .assertIsDisplayed()
        .assertContentDescriptionEquals("Your friend QR code")
    compose
        .onNodeWithTag(C.Tag.friend_code_info, useUnmergedTree = true)
        .onChildren()
        .filterToOne(hasText("Share this code so another student can open your public profile."))
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.friend_code_error).assertDoesNotExist()
  }

  @Test
  fun scanningIsNotOfferedYet() {
    show(shown)

    compose.onAllNodes(hasText("Scan", substring = true, ignoreCase = true)).assertCountEquals(0)
  }

  @Test
  fun unknownDetailsAreLeftOutRatherThanShownEmpty() {
    show(shown.copy(details = ""))

    compose.onNodeWithTag(C.Tag.friend_code_name).assertTextEquals("Léa Martin")
    compose.onNodeWithTag(C.Tag.friend_code_details).assertDoesNotExist()
  }

  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun theDrawnCodeScansBackToTheStudent() {
    show(shown)

    val image = compose.onNodeWithTag(C.Tag.friend_code_qr).captureToImage().asAndroidBitmap()
    // The capture is the code's own square; add the white margin the card gives it on screen.
    val margin = image.width / 6
    val side = image.width + 2 * margin
    val pixels = IntArray(side * side) { WHITE }
    for (y in 0 until image.height) for (x in 0 until image.width) {
      pixels[(y + margin) * side + x + margin] = image.getPixel(x, y)
    }
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))

    assertEquals(QrPayload.Friend(uid), parseQrPayload(QRCodeReader().decode(bitmap).text))
  }

  @Test
  fun closeLeavesTheScreen() {
    var backs = 0
    show(shown) { backs++ }

    compose.onNodeWithTag(C.Tag.friend_code_close).assertContentDescriptionEquals("Close")
    compose.onNodeWithTag(C.Tag.friend_code_close).performClick()
    assertEquals(1, backs)
  }

  @Test
  fun aCodeThatCantBeDrawnExplainsItAndLeadsBackToProfile() {
    var backs = 0
    show(FriendCodeUiState.CouldNotShow) { backs++ }

    compose.onNodeWithTag(C.Tag.friend_code_heading).assertTextEquals("My friend code")
    compose.onNodeWithText(text(R.string.friend_code_error_title)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.friend_code_error_body)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.friend_code_qr).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.friend_code_info).assertDoesNotExist()
    compose
        .onNodeWithTag(C.Tag.friend_code_back_to_profile)
        .performScrollTo()
        .assertTextEquals("Back to profile")
        .performClick()
    assertEquals(1, backs)
  }

  @Config(qualifiers = "w640dp-h360dp")
  @Test
  fun theErrorStillReachesBackToProfileInLandscape() {
    var backs = 0
    show(FriendCodeUiState.CouldNotShow) { backs++ }

    compose.onNodeWithTag(C.Tag.friend_code_back_to_profile).performScrollTo().performClick()
    assertEquals(1, backs)
  }

  @Test
  fun systemBackOnTheRouteLeavesTheScreen() {
    var backs = 0
    val vm = FriendCodeViewModel(FakeAuthRepository(user = null), FakeUserProfileRepository())
    compose.setContent { PolySocialTheme { FriendCodeRoute({ backs++ }, vm) } }

    compose.onNodeWithTag(C.Tag.friend_code_error).assertExists()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertEquals(1, backs)
  }

  @Test
  fun theRouteShowsTheSignedInStudentsCode() {
    val auth = FakeAuthRepository(AuthUser(uid, "fictional@epfl.ch", true, "Léa Martin"))
    val vm = FriendCodeViewModel(auth, FakeUserProfileRepository())
    compose.setContent { PolySocialTheme { FriendCodeRoute({}, vm) } }

    compose.onNodeWithTag(C.Tag.friend_code_qr).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.friend_code_name).assertTextEquals("Léa Martin")
  }

  @Test
  fun studentsProfileOffersMyFriendCodeAboveThePrivacyRow() {
    var opened = 0
    compose.setContent {
      PolySocialTheme {
        Box(Modifier.size(360.dp, 800.dp)) {
          ProfileScreen(LogoutState.Idle, {}, {}, {}, onOpenPrivacy = {}) { opened++ }
        }
      }
    }

    val entry = compose.onNodeWithTag(C.Tag.profile_friend_code_entry)
    entry.assertTextEquals("My friend code").performClick()
    assertEquals(1, opened)
    val friendCode = entry.fetchSemanticsNode().boundsInRoot
    val privacy =
        compose.onNodeWithTag(C.Tag.profile_privacy_entry).fetchSemanticsNode().boundsInRoot
    assertTrue(
        "My friend code ($friendCode) must sit above Privacy & data ($privacy)",
        friendCode.bottom <= privacy.top,
    )
  }

  @Test
  fun aProfileWithoutAFriendCodeDestinationShowsNoRow() {
    compose.setContent { PolySocialTheme { ProfileScreen(LogoutState.Idle, {}, {}, {}, {}) } }

    compose.onNodeWithTag(C.Tag.profile_privacy_entry).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_friend_code_entry).assertDoesNotExist()
  }

  @Test
  fun anAssociationsOrganizationTabHasNoFriendCode() {
    val club =
        Association(
            "fictional-club",
            "Fictional Club",
            AssociationType.CLUB,
            "Fictional activities",
        )
    compose.setContent {
      PolySocialTheme {
        OrganizationScreen(OrganizationUiState.Ready(club), LogoutState.Idle, {}, {}, {}, {}, {})
      }
    }

    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_friend_code_entry).assertDoesNotExist()
  }

  @Test
  fun thePrivacySummarySaysTheCodeHoldsOnlyTheAccountIdentifier() {
    compose.setContent { PolySocialTheme { PrivacyScreen(PrivacyUiState(), {}, {}, {}, {}, {}) } }
    compose
        .onNodeWithText("Your friend code holds only your account identifier.", substring = true)
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun theFullNoticeSaysWhatTheCodeHoldsAndThatItIsNeverUploaded() {
    compose.setContent { PolySocialTheme { FullPrivacyNoticeScreen({}, PrivacyUiState(), {}, {}) } }
    compose
        .onNodeWithText(
            "My friend code shows a QR code that holds only your account identifier, drawn on " +
                "your phone and never uploaded",
            substring = true,
        )
        .performScrollTo()
        .assertIsDisplayed()
  }

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
  }
}
