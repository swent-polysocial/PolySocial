// Contributors: OpenAI Codex (map source binding for #50).
package com.polysocial.model.map

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Replace this binding with #49's event repository adapter when its read API lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MapModule {
  @Binds abstract fun bindEventSource(source: NotConfiguredMapEventSource): MapEventSource
}
