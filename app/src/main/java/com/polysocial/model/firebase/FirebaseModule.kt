// Contributors: OpenAI Codex (replace terminated Firestore instances after logout, #109).
// Contributors: Claude (drafted the shared Firestore provider, #45).
package com.polysocial.model.firebase

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Provides the app's [FirebaseFirestore], shared by every Firestore repository, so tests can
 * replace it in one place.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
  @Provides fun firestore(session: FirestoreSession): FirebaseFirestore = session.database

  @Provides fun sessionCache(session: FirestoreSession): SessionCache = session
}
