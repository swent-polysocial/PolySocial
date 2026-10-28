// Contributors: OpenAI Codex (durable association signup binding, #125).
// Contributors: Claude (Hilt binding for the auth repository); OpenAI Codex
// (GPT-6.1 Sol, medium; retained one binding with lazy Firebase initialization; bound verification
// timing storage and clock for #31).
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
  @Provides
  @Singleton
  fun verificationStore(store: PreferencesVerificationStore): VerificationStore = store

  @Provides
  fun verificationClock(): VerificationClock = VerificationClock { System.currentTimeMillis() }

  @Provides
  @Singleton
  fun associationSignupStore(store: PreferencesAssociationSignupStore): AssociationSignupStore =
      store

  @Provides @Singleton fun authRepository(): AuthRepository = FirebaseAuthRepository()
}
