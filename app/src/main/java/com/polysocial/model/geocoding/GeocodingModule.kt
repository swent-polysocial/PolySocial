// Contributors: Claude (drafted the Hilt binding for geocoding, #111).
package com.polysocial.model.geocoding

import com.polysocial.model.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the app's [GeocodingService]. It is a singleton because Nominatim's
 * one-request-per-second limit and the cache must be shared by the whole app.
 */
@Module
@InstallIn(SingletonComponent::class)
object GeocodingModule {
  @Provides
  @Singleton
  fun geocodingService(network: NetworkMonitor): GeocodingService =
      NominatimGeocodingService(network)
}
