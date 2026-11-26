// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.firebase.ProfileFriendsCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/** Supplies the friends-list reader with the current session's Firestore instance per read. */
@Module
@InstallIn(SingletonComponent::class)
object ProfileFriendsModule {
  @Provides
  @Singleton
  fun profileFriendsRepository(
      db: Provider<FirebaseFirestore>,
      auth: AuthRepository,
      cache: ProfileFriendsCache,
  ): ProfileFriendsRepository = FirestoreProfileFriendsRepository(db::get, auth, cache)
}
