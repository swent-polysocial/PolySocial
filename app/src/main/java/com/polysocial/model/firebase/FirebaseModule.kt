// Contributors: Claude (drafted the shared Firestore provider, #45; resettable on logout, #156).
package com.polysocial.model.firebase

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the app's one [FirestoreProvider], shared by every Firestore repository and by logout,
 * so tests can replace it in one place. There is deliberately no [FirebaseFirestore] binding: an
 * injected instance would stop working once logout terminates it (#156).
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
  @Provides
  @Singleton
  fun firestoreProvider(): FirestoreProvider = ResettableFirestoreProvider {
    FirebaseFirestore.getInstance()
  }
}
