// Contributors: Claude (association names read once and shared by the event screens, #128; an
// account switch during a read, after a test audit).
package com.polysocial.model.association

import com.polysocial.model.auth.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AssociationNames] kept in memory for the whole app, so the Events tab, the map and the event
 * detail share the names they read. Each association has its own lock: loads of the same
 * association wait for each other, and only the first one reads it. Only names are kept, and only
 * for the account that read them ([AuthRepository.currentUser]): another account sees none of them
 * and reads them again.
 */
@Singleton
class CachedAssociationNames
@Inject
constructor(
    private val associations: AssociationRepository,
    private val auth: AuthRepository,
) : AssociationNames {

  /** The names read so far, and the account they were read for. */
  private data class Cache(val viewer: String?, val names: Map<String, String> = emptyMap())

  private val cache = MutableStateFlow(Cache(viewer = null))
  private val locksGuard = Mutex()
  private val locks = mutableMapOf<String, Mutex>()

  override val names: Flow<Map<String, String>> =
      cache.map { if (it.viewer == viewer()) it.names else emptyMap() }.distinctUntilChanged()

  override suspend fun load(uid: String) {
    val lock = locksGuard.withLock { locks.getOrPut(uid) { Mutex() } }
    lock.withLock {
      val viewer = viewer()
      val known = cache.value
      if (known.viewer == viewer && uid in known.names) return
      val name = (associations.getAssociation(uid) as? AssociationResult.Found)?.association?.name
      if (name.isNullOrBlank()) return
      cache.update {
        when {
          // Another account signed in during the read: the name isn't theirs, so it is dropped.
          viewer() != viewer -> it
          it.viewer == viewer -> it.copy(names = it.names + (uid to name))
          else -> Cache(viewer, mapOf(uid to name))
        }
      }
    }
  }

  private fun viewer(): String? = auth.currentUser()?.uid
}

/**
 * Tells Hilt to inject the shared [CachedAssociationNames] wherever [AssociationNames] is needed.
 */
@Module
@InstallIn(SingletonComponent::class)
interface AssociationNamesModule {
  @Binds fun associationNames(names: CachedAssociationNames): AssociationNames
}
