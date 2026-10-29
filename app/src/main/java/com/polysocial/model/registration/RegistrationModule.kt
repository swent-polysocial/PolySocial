// Contributors: Claude Opus 5.5 (drafted the Hilt binding for registrations, #121).
package com.polysocial.model.registration

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Tells Hilt to inject [FirestoreRegistrationRepository] wherever a [RegistrationRepository] is
 * needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object RegistrationModule {
  @Provides
  @Singleton
  fun registrationRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      network: NetworkMonitor,
  ): RegistrationRepository = FirestoreRegistrationRepository({ db.get() }, auth, network)
}
