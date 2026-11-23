// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Hilt binding for usernames, #448).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/** Tells Hilt to inject [FirestoreUsernameRepository] wherever a [UsernameRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object UsernameModule {
  @Provides
  @Singleton
  fun usernameRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      network: NetworkMonitor,
  ): UsernameRepository = FirestoreUsernameRepository({ db.get() }, auth, network)
}
