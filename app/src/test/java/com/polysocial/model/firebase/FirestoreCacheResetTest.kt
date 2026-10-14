// Contributors: OpenAI Codex (real offline disk-cache isolation proof, #109).
package com.polysocial.model.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Uses only the demo emulator endpoint, with networking disabled to exercise persistent cache. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FirestoreCacheResetTest {
  @Test
  fun clearingPersistenceRemovesPreviousSessionDocumentsFromTheNextInstance() = runBlocking {
    withTimeout(30_000) {
      val app =
          FirebaseApp.initializeApp(
              ApplicationProvider.getApplicationContext<Context>(),
              FirebaseOptions.Builder()
                  .setApplicationId("1:0:android:0")
                  .setProjectId("demo-polysocial")
                  .setApiKey("test")
                  .build(),
              "logout-cache-test",
          )
      val session = FirestoreSession {
        FirebaseFirestore.getInstance(app).apply { useEmulator("127.0.0.1", 8080) }
      }
      try {
        val old = session.database
        old.disableNetwork().await()
        // A local mutation enters the cache immediately; its server acknowledgement is deliberately
        // not awaited because the device is offline. No backend data is written by this test.
        old.collection("users")
            .document("fictional-student-one")
            .set(mapOf("privateMarker" to "previous-session"))
        assertEquals(1, old.collection("users").get(Source.CACHE).await().size())
        assertEquals(CacheClearResult.Cleared, session.clear())
        val fresh = session.database
        assertNotSame(old, fresh)
        fresh.disableNetwork().await()
        assertTrue(fresh.collection("users").get(Source.CACHE).await().isEmpty)
      } finally {
        assertEquals("test cache cleanup must succeed", CacheClearResult.Cleared, session.clear())
        app.delete()
      }
    }
  }
}
