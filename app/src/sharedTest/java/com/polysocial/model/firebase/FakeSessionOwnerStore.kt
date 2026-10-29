// Contributors: Claude (Opus 5.5, Anthropic) wrote this test fake, #198; Mohamed Khellaf (reviewed
// and revised).
package com.polysocial.model.firebase

/** In-memory [SessionOwnerStore] for tests, starting with [uid] stored (`null`: a fresh install). */
class FakeSessionOwnerStore(var uid: String? = null) : SessionOwnerStore {
  /** When set, [read] and [write] throw it, like storage that can't be read or written. */
  var failure: RuntimeException? = null

  /** How many times [write] was called. */
  var writes = 0
    private set

  override fun read(): String? {
    failure?.let { throw it }
    return uid
  }

  override fun write(uid: String?) {
    failure?.let { throw it }
    writes++
    this.uid = uid
  }
}
