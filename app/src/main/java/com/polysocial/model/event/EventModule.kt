// Contributors: Claude (drafted the Hilt binding for the event repository).
package com.polysocial.model.event

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
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
  fun eventRepository(auth: AuthRepository, network: NetworkMonitor): EventRepository =
      FirestoreEventRepository(
          FirebaseFirestore.getInstance(),
          auth,
          network,
          Clock.systemDefaultZone(),
      )
}
