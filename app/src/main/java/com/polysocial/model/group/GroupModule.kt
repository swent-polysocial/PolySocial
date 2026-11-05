// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Hilt binding for groups, #232).
package com.polysocial.model.group

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/** Tells Hilt to inject [FirestoreGroupRepository] wherever a [GroupRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object GroupModule {
  @Provides
  @Singleton
  fun groupRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      network: NetworkMonitor,
  ): GroupRepository = FirestoreGroupRepository({ db.get() }, auth, network)
}
