// Contributors: Claude (drafted the Hilt binding for the clock).
package com.polysocial.model.time

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/**
 * Provides the device's [Clock], so classes that need the current time or time zone take it as a
 * parameter and tests can fix it. Not a singleton: each injection reads the time zone the device
 * has at that moment.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
  @Provides fun clock(): Clock = Clock.systemDefaultZone()
}
