// Contributors: Claude (Hilt binding for the profile repository).
package com.polysocial.model.user

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Tells Hilt which [UserProfileRepository] to inject. #34 switches it to the Firestore version. */
@Module
@InstallIn(SingletonComponent::class)
object UserProfileModule {
  @Provides
  @Singleton
  fun userProfileRepository(): UserProfileRepository = PendingUserProfileRepository()
}
