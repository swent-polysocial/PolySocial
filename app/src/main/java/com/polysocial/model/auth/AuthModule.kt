// Contributors: OpenAI Codex (durable association signup binding, #125).
// Contributors: Claude (Fable 5.1, Anthropic) drafted the session guard bindings, #198; Mohamed
// Khellaf (reviewed and revised).
// Contributors: Claude (Hilt binding for the auth repository); OpenAI Codex
// (GPT-6.1 Sol, medium; retained one binding with lazy Firebase initialization; bound verification
// timing storage and clock for #31).
package com.polysocial.model.auth

import com.polysocial.model.firebase.FirestoreSessionGuard
import com.polysocial.model.firebase.PreferencesSessionOwnerStore
import com.polysocial.model.firebase.SessionGuard
import com.polysocial.model.firebase.SessionOwnerStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Tells Hilt to inject [FirebaseAuthRepository] wherever an [AuthRepository] is needed. The session
 * guard is bound here too because it must agree with the signed-in account: a test that replaces
 * [AuthRepository] by uninstalling this module must also bind a [SessionGuard].
 */
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

  @Provides fun authSessionSource(): AuthSessionSource = FirebaseAuthSessionSource()

  @Provides fun sessionOwnerStore(store: PreferencesSessionOwnerStore): SessionOwnerStore = store

  @Provides fun sessionGuard(guard: FirestoreSessionGuard): SessionGuard = guard
}
