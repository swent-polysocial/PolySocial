// Contributors: Claude (drafted the Hilt binding for the network check).
package com.polysocial.model.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Provides the app's [NetworkMonitor]. Tests replace it with a fake through a Hilt test module. */
@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {
  @Binds fun bindNetworkMonitor(monitor: AndroidNetworkMonitor): NetworkMonitor
}
