// Contributors: Claude (screen tests for the placeholder detail screen, #42).
package com.polysocial.ui.navigation

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceholderDetailScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun showsTheTabsDetailPlaceholder() {
    composeTestRule.setContent { PolySocialTheme { PlaceholderDetailScreen(Tab.MAP) } }

    composeTestRule.onNodeWithTag(Tab.MAP.detailTag).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.placeholder_detail_coming_soon))
        .assertIsDisplayed()
  }

  @Test
  fun appliesTheCallersModifierToTheScreen() {
    // A real detail route (e.g. #50's event detail) passes its own modifier; it must reach the
    // screen's container, not be dropped.
    composeTestRule.setContent {
      PolySocialTheme {
        PlaceholderDetailScreen(
            Tab.MAP,
            modifier = Modifier.semantics { contentDescription = "Event details" },
        )
      }
    }

    composeTestRule.onNodeWithTag(Tab.MAP.detailTag).assert(hasContentDescription("Event details"))
  }
}
