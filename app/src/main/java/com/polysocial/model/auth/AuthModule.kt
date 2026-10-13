// Contributors: Claude (Hilt binding for the auth repository; Firestore provider for logout,
// #156); OpenAI Codex (GPT-6.1 Sol, medium; retained one binding with lazy Firebase
// initialization).
package com.polysocial.model.auth

import com.polysocial.model.firebase.FirestoreProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Tells Hilt to inject [FirebaseAuthRepository] wherever an [AuthRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
  @Provides
  @Singleton
  fun authRepository(firestore: FirestoreProvider): AuthRepository =
      FirebaseAuthRepository(firestore)
}
