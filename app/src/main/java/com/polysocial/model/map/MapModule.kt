// Contributors: OpenAI Codex (map source binding for #50).
package com.polysocial.model.map

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Connects the production map to the event repository without exposing Firebase to the UI. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MapModule {
  @Binds abstract fun bindEventSource(source: RepositoryMapEventSource): MapEventSource
}
