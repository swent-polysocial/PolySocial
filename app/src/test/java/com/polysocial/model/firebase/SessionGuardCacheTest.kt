// Contributors: Claude (Opus 5.5, Anthropic) wrote this test and restarted the session for real
// after review, #198; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.polysocial.model.auth.FakeAuthSessionSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The guard against a real Firestore disk cache, offline (like [FirestoreCacheResetTest]): a
 * document student A left in the cache is gone before student B can read, while A's cache survives
 * a restart of A's own session. Each restart opens a new session over the same disk cache, so no
 * in-memory "just cleared" state carries over. No backend is reached and nothing is written to one.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SessionGuardCacheTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun anotherAccountNeverReadsThePreviousAccountsCachedDocument() = runBlocking {
    withTimeout(30_000) {
      val app =
          FirebaseApp.initializeApp(
              context,
              FirebaseOptions.Builder()
                  .setApplicationId("1:0:android:0")
                  .setProjectId("demo-polysocial")
                  .setApiKey("test")
                  .build(),
              "session-guard-cache-test",
          )
      var session = newSession(app)
      val owner = PreferencesSessionOwnerStore(context)
      try {
        assertEquals(SessionGuardResult.Ready, guard(owner, session).ensureSession(STUDENT_A))
        writeLocally(session, STUDENT_A)
        assertTrue(cachedProfileExists(session, STUDENT_A))

        // Same account after a restart: the cache stays for offline use.
        session = restart(app, session)
        assertEquals(SessionGuardResult.Ready, guard(owner, session).ensureSession(STUDENT_A))
        assertTrue(cachedProfileExists(session, STUDENT_A))

        // Another account after a restart: cleared before it can read.
        session = restart(app, session)
        assertEquals(SessionGuardResult.Ready, guard(owner, session).ensureSession(STUDENT_B))
        assertFalse(cachedProfileExists(session, STUDENT_A))
        assertEquals(STUDENT_B, owner.read())
      } finally {
        assertEquals("test cache cleanup must succeed", CacheClearResult.Cleared, session.clear())
        owner.write(null)
        app.delete()
      }
    }
  }

  private fun newSession(app: FirebaseApp) = FirestoreSession {
    FirebaseFirestore.getInstance(app).apply { useEmulator("127.0.0.1", 8080) }
  }

  /**
   * As if the process ended: the open instance is stopped without clearing its disk cache, and a
   * new session and guard start over it, with only the stored owner remembered.
   */
  private suspend fun restart(app: FirebaseApp, previous: FirestoreSession): FirestoreSession {
    previous.database.terminate().await()
    return newSession(app)
  }

  private fun guard(owner: SessionOwnerStore, session: FirestoreSession) =
      FirestoreSessionGuard(FakeAuthSessionSource(), owner, session)

  /**
   * A local write enters the cache at once; its server acknowledgement is deliberately not awaited
   * because the network is off, so nothing reaches a backend.
   */
  private suspend fun writeLocally(session: FirestoreSession, uid: String) {
    val database = session.database
    database.disableNetwork().await()
    database.collection("users").document(uid).set(mapOf("displayName" to "Test Student"))
  }

  /** Whether `users/[uid]` can be read from the cache alone, as offline. */
  private suspend fun cachedProfileExists(session: FirestoreSession, uid: String): Boolean {
    val database = session.database
    database.disableNetwork().await()
    // A cache query, as in FirestoreCacheResetTest: a single-document cache read never completed
    // under Robolectric here.
    return database.collection("users").get(Source.CACHE).await().documents.any { it.id == uid }
  }

  private companion object {
    const val STUDENT_A = "fictional-student-a"
    const val STUDENT_B = "fictional-student-b"
  }
}
