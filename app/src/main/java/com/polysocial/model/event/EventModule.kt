// Contributors: Claude (drafted the Hilt binding for the event repository; shared Firestore
// instance, injected clock and profiles, #45).
package com.polysocial.model.event

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
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
      db: FirebaseFirestore,
      auth: AuthRepository,
      profiles: UserProfileRepository,
      network: NetworkMonitor,
      clock: Clock,
  ): EventRepository = FirestoreEventRepository(db, auth, profiles, network, clock)
}
