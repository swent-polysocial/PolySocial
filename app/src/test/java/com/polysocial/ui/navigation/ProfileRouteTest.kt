// Contributors: Claude Opus 5.5 (wrote these tests of the one place that picks My profile or a
// student's public profile, #501); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Tab.profileRouteOf] opens My profile for your own UID and the public profile otherwise (#501).
 */
class ProfileRouteTest {
  @Test
  fun yourOwnUid_opensMyProfileInTheSameTab() {
    for (tab in Tab.entries) {
      assertEquals(tab.myProfileRoute, tab.profileRouteOf("me", signedInUid = "me"))
    }
  }

  @Test
  fun anotherStudentsUid_opensTheirPublicProfileInTheSameTab() {
    for (tab in Tab.entries) {
      assertEquals(tab.studentProfileRoute("peer"), tab.profileRouteOf("peer", signedInUid = "me"))
    }
  }

  @Test
  fun withNoSignedInUid_everyUidOpensAPublicProfile() {
    assertEquals(
        Tab.EVENTS.studentProfileRoute("me"),
        Tab.EVENTS.profileRouteOf("me", signedInUid = null),
    )
  }
}
