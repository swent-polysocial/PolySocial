// Contributors: Claude Opus 5.5 (drafted the Hilt binding for friendships, #357); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.model.friend

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/** Tells Hilt to inject [FirestoreFriendRepository] wherever a [FriendRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object FriendModule {
  @Provides
  @Singleton
  fun friendRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      network: NetworkMonitor,
  ): FriendRepository = FirestoreFriendRepository({ db.get() }, auth, network)
}
