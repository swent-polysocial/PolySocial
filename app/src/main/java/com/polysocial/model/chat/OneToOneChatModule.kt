// Contributors: Claude
package com.polysocial.model.chat

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
 * Tells Hilt to inject [FirestoreOneToOneChatRepository] wherever a [OneToOneChatRepository] is
 * needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object OneToOneChatModule {
  @Provides
  @Singleton
  fun oneToOneChatRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      network: NetworkMonitor,
  ): OneToOneChatRepository = FirestoreOneToOneChatRepository({ db.get() }, auth, network)
}
