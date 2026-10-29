// Contributors: Claude (Opus 5.5, Anthropic) wrote this test fake, #198; Mohamed Khellaf (reviewed
// and revised).
package com.polysocial.model.firebase

/** In-memory [SessionOwnerStore] for tests, starting with [uid] stored (`null`: a fresh install). */
class FakeSessionOwnerStore(var uid: String? = null) : SessionOwnerStore {
  /** How many times [write] was called. */
  var writes = 0
    private set

  override fun read(): String? = uid

  override fun write(uid: String?) {
    writes++
    this.uid = uid
  }
}
