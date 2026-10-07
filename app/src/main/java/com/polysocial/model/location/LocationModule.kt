// Contributors: OpenAI Codex (Hilt location service binding for #51).
package com.polysocial.model.location

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Supplies one-off Android location access to ViewModels through [LocationService]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
  @Binds @Singleton abstract fun locationService(service: AndroidLocationService): LocationService
}
