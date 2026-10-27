// Contributors: Claude (Hilt binding for the association repository, #126).
package com.polysocial.model.association

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Tells Hilt to inject [FirestoreAssociationRepository] wherever an [AssociationRepository] is
 * needed. It resolves the current Firestore instance per operation, so it keeps working with the
 * replacement instance after a logout clears the cache (#156).
 */
@Module
@InstallIn(SingletonComponent::class)
object AssociationModule {
  @Provides
  @Singleton
  fun associationRepository(db: Provider<FirebaseFirestore>): AssociationRepository =
      FirestoreAssociationRepository {
        db.get()
      }
}
