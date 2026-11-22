// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the signed-in student's own
// profile screen, #495).
package com.polysocial.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class MyProfileScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val me = PublicProfile("me", "Demo Student", "IN", "BA3", setOf("sports", "cinema"))
  private val calls = mutableListOf<String>()

  private fun show(state: StudentProfileUiState) {
    compose.setContent {
      PolySocialTheme {
        MyProfileScreen(
            state,
            onBack = { calls += "back" },
            onEdit = { calls += "edit" },
            onRetry = { calls += "retry" },
        )
      }
    }
  }

  private fun string(resource: Int) = context.getString(resource)

  @Test
  fun found_showsMyProfileAsOthersSeeIt_withAnEditButton() {
    show(StudentProfileUiState.Found(me))

    compose.onNodeWithText(string(R.string.my_profile_title)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.student_profile_name).assertTextEquals("Demo Student")
    compose.onNodeWithTag(C.Tag.student_profile_details).assertTextEquals("IN · BA3")
    compose.onAllNodesWithTag(C.Tag.student_profile_interest).assertCountEquals(2)
    compose.onNodeWithTag(C.Tag.my_profile_edit).assertIsDisplayed()
  }

  @Test
  fun theEditButtonOpensEditProfile_andBackLeaves() {
    show(StudentProfileUiState.Found(me))

    compose.onNodeWithTag(C.Tag.my_profile_edit).performClick()
    compose.onNodeWithTag(C.Tag.my_profile_back).performClick()

    assertEquals(listOf("edit", "back"), calls)
  }

  @Test
  fun loading_showsTheSharedLoadingState_withoutEdit() {
    show(StudentProfileUiState.Loading)

    compose.onNodeWithTag(C.Tag.student_profile_loading).assertIsDisplayed()
    compose.onAllNodesWithTag(C.Tag.my_profile_edit).assertCountEquals(0)
  }

  @Test
  fun aMissingPublicProfile_neverSaysTheStudentCantBeFound_andStillOffersEdit() {
    show(StudentProfileUiState.NotFound)

    compose.onNodeWithTag(C.Tag.my_profile_not_ready).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.my_profile_not_ready_title)).assertIsDisplayed()
    compose.onAllNodesWithTag(C.Tag.student_profile_not_found).assertCountEquals(0)
    compose.onNodeWithTag(C.Tag.my_profile_edit).assertIsDisplayed()
  }

  @Test
  fun couldNotLoad_offersRetry_withoutEdit() {
    show(StudentProfileUiState.CouldNotLoad)

    compose.onNodeWithTag(C.Tag.student_profile_error).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.retry)).performClick()

    assertEquals(listOf("retry"), calls)
    compose.onAllNodesWithTag(C.Tag.my_profile_edit).assertCountEquals(0)
  }
}
