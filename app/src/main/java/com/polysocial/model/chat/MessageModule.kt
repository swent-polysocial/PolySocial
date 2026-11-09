// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Hilt binding for group chats, #237).
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

/** Tells Hilt to inject [FirestoreMessageRepository] wherever a [MessageRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object MessageModule {
  @Provides
  @Singleton
  fun messageRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      network: NetworkMonitor,
  ): MessageRepository = FirestoreMessageRepository({ db.get() }, auth, network)
}
