// Contributors: OpenAI Codex (bound an in-memory chat repository in Hilt tests, #238).
package com.polysocial.model.chat

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Hilt UI tests use local chat data even when they uninstall FirebaseModule. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [MessageModule::class])
object TestMessageModule {
  @Provides @Singleton fun messages(): MessageRepository = FakeMessageRepository()
}
