// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests, #198; Mohamed Khellaf (reviewed and
// revised).
package com.polysocial.model.firebase

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The cache owner survives a restart in private preferences; a damaged value matches no account.
 */
@RunWith(AndroidJUnit4::class)
// A plain application: the real one starts its own session guard on the same preferences.
@Config(application = Application::class)
class PreferencesSessionOwnerStoreTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun freshInstall_hasNoOwner() {
    assertNull(PreferencesSessionOwnerStore(context).read())
  }

  @Test
  fun writtenOwner_survivesARestart() {
    PreferencesSessionOwnerStore(context).write("student-a")

    assertEquals("student-a", PreferencesSessionOwnerStore(context).read())
  }

  @Test
  fun signedOut_isStoredAsNoOwner() {
    val store = PreferencesSessionOwnerStore(context)
    store.write("student-a")

    store.write(null)

    assertNull(PreferencesSessionOwnerStore(context).read())
  }

  @Test
  fun unreadableValue_matchesNoAccountOrSignedOut() {
    context.getSharedPreferences("session_guard", Context.MODE_PRIVATE).edit { putInt("uid", 42) }

    val owner = PreferencesSessionOwnerStore(context).read()

    // Neither signed out nor any UID, so the guard clears for whoever is signed in.
    assertNotEquals(null, owner)
    assertNotEquals("42", owner)
    assertEquals("", owner)
  }
}
