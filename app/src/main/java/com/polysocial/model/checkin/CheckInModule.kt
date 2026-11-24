// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Hilt binding for check-ins, #467).
package com.polysocial.model.checkin

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Provider
import javax.inject.Singleton

/** Tells Hilt to inject [FirestoreCheckInRepository] wherever a [CheckInRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object CheckInModule {
  @Provides
  @Singleton
  fun checkInRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      network: NetworkMonitor,
      clock: Clock,
  ): CheckInRepository = FirestoreCheckInRepository({ db.get() }, auth, network, clock)
}
