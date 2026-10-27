// Contributors: Claude (test fake for the shared association names, #128).
package com.polysocial.model.association

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [AssociationNames] for tests: [load] makes a name from [known] appear in [names] and
 * records the UID; a UID missing from [known] stays unknown, like a failed read. Tests of the
 * caching itself use `CachedAssociationNames` with a [FakeAssociationRepository] instead.
 */
class FakeAssociationNames(var known: Map<String, String> = emptyMap()) : AssociationNames {
  private val loaded = MutableStateFlow<Map<String, String>>(emptyMap())

  /** The UIDs passed to [load], in call order. */
  val requestedUids = mutableListOf<String>()

  override val names: Flow<Map<String, String>> = loaded

  override suspend fun load(uid: String) {
    requestedUids += uid
    known[uid]?.let { name -> loaded.value += (uid to name) }
  }
}
