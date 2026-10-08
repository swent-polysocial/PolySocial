// Contributors: Claude (drafted the shared Firestore provider, #45).
package com.polysocial.model.firebase

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the app's [FirebaseFirestore], shared by every Firestore repository, so tests can
 * replace it in one place.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
  @Provides @Singleton fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
}
