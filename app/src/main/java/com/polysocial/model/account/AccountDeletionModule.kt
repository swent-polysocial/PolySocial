// Contributors: Claude (Opus 5.5, Anthropic) drafted the Hilt binding for account deletion, #206;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Tells Hilt to inject [FirebaseAccountDeletionRepository] wherever an [AccountDeletionRepository]
 * is needed. Firebase is resolved on the first deletion, not here, and the session's Firestore
 * instance on each deletion, so it follows the replacement instance after a logout (#156).
 */
@Module
@InstallIn(SingletonComponent::class)
object AccountDeletionModule {
  @Provides
  @Singleton
  fun accountDeletionRepository(db: Provider<FirebaseFirestore>): AccountDeletionRepository =
      FirebaseAccountDeletionRepository(
          authProvider = { FirebaseAuth.getInstance() },
          firestore = { db.get() },
      )
}
