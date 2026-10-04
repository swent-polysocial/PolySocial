// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial.model.user

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingUserProfileRepositoryTest {
  @Test
  fun getProfile_reportsTheRequestedUidAsFound() = runTest {
    val result = PendingUserProfileRepository().getProfile("u1")

    assertEquals(ProfileResult.Found(UserProfile(uid = "u1", email = "")), result)
  }
}
