// Contributors: Claude (Hilt binding for the auth repository); OpenAI Codex
// (GPT-6.1 Sol, medium; retained one binding with lazy Firebase initialization).
package com.polysocial.model.auth

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Tells Hilt to inject [FirebaseAuthRepository] wherever an [AuthRepository] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
  @Provides @Singleton fun authRepository(): AuthRepository = FirebaseAuthRepository()
}
