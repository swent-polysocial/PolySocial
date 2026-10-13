// Contributors: Claude (wrote these tests, #156).
package com.polysocial.model.firebase

import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ResettableFirestoreProviderTest {
  private val first = mockk<FirebaseFirestore>()
  private var created = 0
  private val provider = ResettableFirestoreProvider {
    created++
    first
  }

  @Test
  fun theInstance_isCreatedOnFirstUseOnly() = runTest {
    assertEquals(0, created)

    assertSame(first, provider.get())
    assertSame(first, provider.get())
    assertEquals(1, created)
  }
}
