// Contributors: Claude (wrote these wiring tests for geocoding and the location picker, #111).
package com.polysocial.model.geocoding

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.ui.event.create.LocationPickerViewModel
import com.polysocial.ui.event.create.PinPlaceState
import com.polysocial.ui.event.create.PlaceSearchState
import com.polysocial.utils.FirebaseStartupRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app geocodes with Nominatim through one shared instance (its rate limit and cache only work
 * app-wide if there is a single one), and Hilt can build the location picker's ViewModel.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class GeocodingModuleTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()

  @get:Rule(order = 2) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Inject lateinit var first: GeocodingService
  @Inject lateinit var second: GeocodingService

  @Test
  fun theApp_geocodesWithOneSharedNominatimService() {
    hiltRule.inject()

    assertTrue(first is NominatimGeocodingService)
    assertSame(first, second)
  }

  @Test
  fun hilt_buildsTheLocationPickerViewModel() {
    var viewModel: LocationPickerViewModel? = null
    composeTestRule.activity.setContent { viewModel = hiltViewModel() }
    composeTestRule.waitForIdle()

    assertNotNull(viewModel)
    val state = viewModel!!.uiState.value
    assertEquals(LocationPickerViewModel.LAUSANNE, state.pin)
    assertEquals(PlaceSearchState.Idle, state.search)
    assertEquals(PinPlaceState.Resolving, state.pinPlace)
  }
}
