// Contributors: Claude (wrote this test).
package com.polysocial.model.time

import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockModuleTest {

  private val originalZone = TimeZone.getDefault()

  @After
  fun restoreZone() {
    TimeZone.setDefault(originalZone)
  }

  @Test
  fun theClock_usesTheDeviceTimeZoneAtInjection() {
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
    assertEquals(ZoneId.of("Europe/Zurich"), ClockModule.clock().zone)

    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    assertEquals(ZoneId.of("Asia/Tokyo"), ClockModule.clock().zone)
  }
}
