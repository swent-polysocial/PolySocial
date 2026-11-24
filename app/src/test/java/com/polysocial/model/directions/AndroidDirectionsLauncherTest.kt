// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of opening the directions, #391,
// following AndroidPrivacyContactLauncherTest).
package com.polysocial.model.directions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDirectionsLauncherTest {
  private class MapsContext(private val failure: RuntimeException? = null) :
      ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
    var openedIntent: Intent? = null

    override fun startActivity(intent: Intent) {
      failure?.let { throw it }
      openedIntent = intent
    }
  }

  private val place = Coordinates(46.5191, 6.5668)

  @Test
  fun opensTheTransitLinkAsAViewIntent() {
    val context = MapsContext()

    assertTrue(AndroidDirectionsLauncher(context).openTransitDirections(place))

    val intent = requireNotNull(context.openedIntent)
    assertEquals(Intent.ACTION_VIEW, intent.action)
    assertEquals(transitDirectionsLink(place), intent.data?.toString())
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun noAppToOpenItDoesNotCrash() {
    val context = MapsContext(ActivityNotFoundException())

    assertFalse(AndroidDirectionsLauncher(context).openTransitDirections(place))
    assertNull(context.openedIntent)
  }

  @Test
  fun aBlockedAppDoesNotCrash() {
    val context = MapsContext(SecurityException())

    assertFalse(AndroidDirectionsLauncher(context).openTransitDirections(place))
    assertNull(context.openedIntent)
  }

  @Test
  fun aPlaceWithNoLinkOpensNothing() {
    val context = MapsContext()

    assertFalse(
        AndroidDirectionsLauncher(context).openTransitDirections(Coordinates(Double.NaN, 0.0))
    )
    assertNull(context.openedIntent)
  }
}
