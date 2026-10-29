// Contributors: Claude (Opus 5.5, Anthropic) wrote this test fake, #198; Mohamed Khellaf (reviewed
// and revised).
package com.polysocial.model.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [AuthSessionSource] for tests: emits [uid] when collection starts and each time the test changes
 * it, like Firebase's auth state listener. Starts signed out unless [initial] is given.
 */
class FakeAuthSessionSource(initial: String? = null) : AuthSessionSource {
  private val current = MutableStateFlow(initial)

  /** The signed-in UID; setting it is a sign-in, a sign-out or an account switch. */
  var uid: String?
    get() = current.value
    set(value) {
      current.value = value
    }

  override fun uidChanges(): Flow<String?> = current
}
