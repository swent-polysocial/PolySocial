// Contributors: OpenAI Codex (replace terminated Firestore instances and document provider
// injection, #156).
// Contributors: Claude (drafted the shared Firestore provider, #45).
package com.polysocial.model.firebase

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Provides the current session's [FirebaseFirestore]. Inject `Provider<FirebaseFirestore>` into
 * repository bindings and resolve it per operation so cleanup can replace the database. Direct
 * injection retains an obsolete instance after cleanup and can fail at injection time while cleanup
 * blocks access.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
  @Provides fun firestore(session: FirestoreSession): FirebaseFirestore = session.database

  @Provides fun sessionCache(session: FirestoreSession): SessionCache = session
}
