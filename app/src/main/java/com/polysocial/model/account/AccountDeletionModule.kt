// Contributors: Claude (Opus 5.5, Anthropic) drafted the Hilt binding for account deletion, #206;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.account

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Tells Hilt to inject [FirebaseAccountDeletionRepository] wherever an [AccountDeletionRepository]
 * is needed. Firebase is resolved on the first deletion, not here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AccountDeletionModule {
  @Provides
  @Singleton
  fun accountDeletionRepository(): AccountDeletionRepository = FirebaseAccountDeletionRepository()
}
