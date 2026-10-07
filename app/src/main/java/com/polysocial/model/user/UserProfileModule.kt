// Contributors: Claude (Hilt binding for the profile repository; Firestore version, #34).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Tells Hilt to inject [FirestoreUserProfileRepository] wherever a [UserProfileRepository] is
 * needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object UserProfileModule {
  @Provides
  @Singleton
  fun userProfileRepository(): UserProfileRepository =
      FirestoreUserProfileRepository(FirebaseFirestore.getInstance())
}
