// Contributors: Claude Opus 5.5 (drafted the My friend code screen tests, #339; the quiet zone,
// and black-on-white tests after review);
// Konstantinos Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.profile

import android.graphics.Bitmap
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
import kotlin.math.abs
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

    assertScansAsIs(uid)
  }

  // At 3x, ZXing draws a one-character UID as 25 modules: the drawing itself must keep
  // the 4-module quiet zone, since the card's name sits right above the code (#384 review).
  @Config(qualifiers = "w360dp-h800dp-xxhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun aShortCodeAtThreeTimesDensityScansAsDrawn() {
    show(shown.copy(code = encodeQrCode(friendQrPayload("a")!!)!!))

    assertScansAsIs("a")
  }

  /**
   * Decodes the code exactly as the screen draws it, with no margin added, and checks it is pure
   * black on pure white (whatever the theme) with a quiet zone of at least 4 modules on each side.
   */
  private fun assertScansAsIs(expectedUid: String) {
    val image = compose.onNodeWithTag(C.Tag.friend_code_qr).captureToImage().asAndroidBitmap()
    val pixels = IntArray(image.width * image.height)
    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
    assertTrue(
        "the code must be drawn in pure black and white",
        pixels.all { it == BLACK || it == WHITE },
    )
    assertQuietZone(image, encodeQrCode(friendQrPayload(expectedUid)!!)!!.size)

    val source = RGBLuminanceSource(image.width, image.height, pixels)
    val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    assertEquals(QrPayload.Friend(expectedUid), parseQrPayload(text))
  }

  private fun assertQuietZone(image: Bitmap, modules: Int) {
    var left = image.width
    var top = image.height
    var right = -1
    var bottom = -1
    for (y in 0 until image.height) for (x in 0 until image.width) {
      if (image.getPixel(x, y) == BLACK) {
        left = minOf(left, x)
        top = minOf(top, y)
        right = maxOf(right, x)
        bottom = maxOf(bottom, y)
      }
    }
    val quietZone = 4 * (right - left + 1) / modules
    val margins = listOf(left, top, image.width - 1 - right, image.height - 1 - bottom)
    assertTrue(
        "each margin $margins must hold 4 modules ($quietZone px)",
        margins.all { it >= quietZone },
    )
    assertTrue("the code must be centred: $margins", abs(margins[0] - margins[2]) <= 1)
    assertTrue("the code must be centred: $margins", abs(margins[1] - margins[3]) <= 1)
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
    const val BLACK = 0xFF000000.toInt()
  }
}
