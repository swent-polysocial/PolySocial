// Contributors: OpenAI Codex (association intent persistence, privacy and failure tests, #125).
package com.polysocial.model.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class AssociationSignupStoreTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val preferences = context.getSharedPreferences("association_signup", Context.MODE_PRIVATE)
  private val store = PreferencesAssociationSignupStore(context)

  @Before
  fun clearPreferences() {
    assertTrue(preferences.edit().clear().commit())
  }

  @Test
  fun rememberedIntentSurvivesNewStoreInstanceAndIsIsolated() = runTest {
    assertFalse(store.contains("club@example.org"))
    store.remember("club@example.org")
    val recreated = PreferencesAssociationSignupStore(context)
    assertTrue(recreated.contains("club@example.org"))
    assertFalse(recreated.contains("other@example.org"))
  }

  @Test
  fun mailboxNormalizationAppliesToRememberReadAndClear() = runTest {
    store.remember("  CLUB@EXAMPLE.ORG ")
    assertTrue(store.contains("club@example.org"))
    store.clear(" Club@Example.Org ")
    assertFalse(PreferencesAssociationSignupStore(context).contains("club@example.org"))
  }

  @Test
  fun clearOnlyRemovesTheRequestedAccount() = runTest {
    store.remember("club@example.org")
    store.remember("other@example.org")
    store.clear("club@example.org")
    assertFalse(store.contains("club@example.org"))
    assertTrue(store.contains("other@example.org"))
  }

  @Test
  fun storageContainsOnlyDigestAndBooleanMarker() = runTest {
    store.remember("club@example.org")
    val entry = preferences.all.entries.single()
    assertTrue(entry.key.matches(Regex("[0-9a-f]{64}")))
    assertFalse(entry.key.contains("club"))
    assertEquals(true, entry.value)
  }

  @Test
  fun failedDurableWriteAndClearAreReported() = runTest {
    val context = mockk<Context>()
    val preferences = mockk<SharedPreferences>()
    val editor = mockk<SharedPreferences.Editor>()
    every { context.getSharedPreferences("association_signup", Context.MODE_PRIVATE) } returns
        preferences
    every { preferences.edit() } returns editor
    every { editor.putBoolean(any(), true) } returns editor
    every { editor.remove(any()) } returns editor
    every { editor.commit() } returns false
    val failing = PreferencesAssociationSignupStore(context)
    try {
      failing.remember("club@example.org")
      fail("A failed commit must stop account creation")
    } catch (failure: IOException) {
      assertTrue(failure.message!!.contains("save"))
    }
    try {
      failing.clear("club@example.org")
      fail("A failed removal must be reported")
    } catch (failure: IOException) {
      assertTrue(failure.message!!.contains("clear"))
    }
  }

  @Test
  fun emptyDefaultNeverClassifiesAnAccountAsAssociation() = runTest {
    EmptyAssociationSignupStore.remember("club@example.org")
    assertFalse(EmptyAssociationSignupStore.contains("club@example.org"))
    EmptyAssociationSignupStore.clear("club@example.org")
    assertFalse(EmptyAssociationSignupStore.contains("club@example.org"))
  }
}
