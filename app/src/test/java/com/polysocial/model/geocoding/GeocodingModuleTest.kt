// Contributors: Claude (wrote this wiring test for geocoding, #111).
package com.polysocial.model.geocoding

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.utils.FirebaseStartupRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app geocodes with Nominatim through one shared instance (its rate limit and cache only work
 * app-wide if there is a single one).
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class GeocodingModuleTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()

  @Inject lateinit var first: GeocodingService
  @Inject lateinit var second: GeocodingService

  @Test
  fun theApp_geocodesWithOneSharedNominatimService() {
    hiltRule.inject()

    assertTrue(first is NominatimGeocodingService)
    assertSame(first, second)
  }
}
