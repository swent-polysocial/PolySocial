// Contributors: Claude (drafted the Hilt binding for the event repository; shared Firestore
// instance, injected clock and profiles, #45; Firestore provider, #156).
package com.polysocial.model.event

import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.firebase.FirestoreProvider
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.UserProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/** Tells Hilt to inject [FirestoreEventRepository] wherever an [EventRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object EventModule {
  @Provides
  @Singleton
  fun eventRepository(
      firestore: FirestoreProvider,
      auth: AuthRepository,
      profiles: UserProfileRepository,
      network: NetworkMonitor,
      clock: Clock,
  ): EventRepository = FirestoreEventRepository(firestore, auth, profiles, network, clock)
}
