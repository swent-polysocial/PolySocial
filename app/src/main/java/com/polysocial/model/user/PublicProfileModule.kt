// Contributors: Claude (Hilt binding for the public profile repository, #229).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Tells Hilt to inject [FirestorePublicProfileRepository] wherever a [PublicProfileRepository] is
 * needed. It resolves the current Firestore instance per operation, so it keeps working with the
 * replacement instance after a logout clears the cache (#156).
 */
@Module
@InstallIn(SingletonComponent::class)
object PublicProfileModule {
  @Provides
  @Singleton
  fun publicProfileRepository(db: Provider<FirebaseFirestore>): PublicProfileRepository =
      FirestorePublicProfileRepository {
        db.get()
      }
}
