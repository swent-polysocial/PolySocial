// Contributors: OpenAI Codex (isolated SDK factories for real-activity startup tests).
package com.polysocial.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.rules.ExternalResource

/**
 * Keeps real Hilt activity smoke tests offline once startup resolves repository dependencies. Mock
 * SDK factories with a signed-out account; no profile read is expected. Startup routing itself is
 * tested separately with fake repositories, not through SDK mocks.
 */
class FirebaseStartupRule : ExternalResource() {
  override fun before() {
    mockkStatic(FirebaseAuth::class, FirebaseFirestore::class)
    val auth = mockk<FirebaseAuth>()
    val firestore = mockk<FirebaseFirestore>()
    every { FirebaseAuth.getInstance() } returns auth
    every { auth.currentUser } returns null
    every { FirebaseFirestore.getInstance() } returns firestore
  }

  override fun after() {
    unmockkStatic(FirebaseAuth::class, FirebaseFirestore::class)
  }
}
