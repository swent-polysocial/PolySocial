// Contributors: Claude (wrote this test, #46).
package com.polysocial.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.resources.C
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** In the real app, the "+" opens the real Create Event screen, with its ViewModel from Hilt. */
@HiltAndroidTest
@UninstallModules(EventModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class CreateEventEntryHiltTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @BindValue @JvmField val events: EventRepository = FakeEventRepository()

  @Test
  fun thePlusButton_opensTheCreateEventForm() {
    composeTestRule.onNodeWithTag(C.Tag.create_event_button).performClick()

    composeTestRule.onNodeWithTag(C.Tag.create_event_title).assertIsDisplayed()

    composeTestRule.onNodeWithTag(C.Tag.create_event_close).performClick()

    composeTestRule.onNodeWithTag(Tab.EVENTS.screenTag).assertIsDisplayed()
  }
}
