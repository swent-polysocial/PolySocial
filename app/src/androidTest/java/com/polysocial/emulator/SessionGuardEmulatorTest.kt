// Contributors: Claude (Opus 5.5, Anthropic) wrote this test, #198; Mohamed Khellaf (reviewed and
// revised).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.polysocial.model.auth.FirebaseAuthSessionSource
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.FirestoreSession
import com.polysocial.model.firebase.FirestoreSessionGuard
import com.polysocial.model.firebase.PreferencesSessionOwnerStore
import com.polysocial.model.firebase.SessionGuardResult
import com.polysocial.model.user.FirestoreUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.USERS_COLLECTION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The backend part of #198: with the real [FirestoreSessionGuard] watching Firebase Auth, a student
 * who signs in after another one can't read the other's profile from Firestore's disk cache, which
 * the Security Rules can't protect.
 *
 * [FirebaseEmulator]'s shared Firestore keeps its cache in memory and lives for the whole run, so
 * this test has its own [FirebaseApp], connected to the same emulators, with Firestore's default
 * disk cache: the cache the app uses, and one the guard may clear without breaking other tests.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class SessionGuardEmulatorTest {
  @get:Rule val emulator = FirebaseEmulator()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val owner = PreferencesSessionOwnerStore(context)
  private val session = FirestoreSession {
    FirebaseFirestore.getInstance(GuardedApp.app).apply {
      useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
    }
  }
  private val guard =
      FirestoreSessionGuard(FirebaseAuthSessionSource { GuardedApp.auth }, owner, session)
  private val profiles = FirestoreUserProfileRepository { session.database }

  /** Like the app's: the guard reacts inside Firebase's main-thread auth callback. */
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  @Before
  fun startSignedOutWithAnEmptyCache() = runBlocking {
    GuardedApp.auth.signOut()
    owner.write(null)
    assertEquals(
        "Couldn't empty this test's Firestore cache before starting",
        CacheClearResult.Cleared,
        session.clear(),
    )
  }

  @After
  fun stopAndClean() = runBlocking {
    appScope.cancel()
    GuardedApp.auth.signOut()
    owner.write(null)
    assertEquals(CacheClearResult.Cleared, session.clear())
  }

  @Test
  fun nextStudentCannotReadThePreviousStudentsCachedProfile_andTheFirstStillCan() = runBlocking {
    guard.start(appScope)

    val first = emulator.signInVerifiedUser(FIRST_EMAIL, auth = GuardedApp.auth).uid
    awaitGuardRecorded(first)
    assertEquals(SessionGuardResult.Ready, guard.ensureSession(first))
    createProfileAsAdmin(first, FIRST_EMAIL)
    // Read from the server, as app start does; Firestore keeps it in the disk cache.
    assertTrue(profiles.getProfile(first) is ProfileResult.Found)
    assertTrue("the first profile must be cached for this test to mean anything", isCached(first))

    // A different student signs in on the same device, without logout's clear.
    val second = emulator.signInVerifiedUser(SECOND_EMAIL, auth = GuardedApp.auth).uid
    awaitGuardRecorded(second)
    assertEquals(SessionGuardResult.Ready, guard.ensureSession(second))

    assertFalse("the second student read the first one's cached profile", isCached(first))
    // The server refuses it too (Security Rules), so the second student can't get it at all.
    assertFalse(profiles.getProfile(first) is ProfileResult.Found)

    // The first student signs back in: the cache is cleared again and the profile still loads.
    GuardedApp.auth.signInWithEmailAndPassword(FIRST_EMAIL, FirebaseEmulator.TEST_PASSWORD).await()
    GuardedApp.auth.currentUser!!.getIdToken(true).await()
    awaitGuardRecorded(first)
    assertEquals(SessionGuardResult.Ready, guard.ensureSession(first))
    assertTrue(profiles.getProfile(first) is ProfileResult.Found)
  }

  /** Waits until the guard has handled [uid]'s sign-in, which it records only after its clear. */
  private suspend fun awaitGuardRecorded(uid: String) {
    withTimeoutOrNull(GUARD_TIMEOUT_MS) { while (owner.read() != uid) delay(POLL_MS) }
        ?: fail("The session guard didn't handle the sign-in within $GUARD_TIMEOUT_MS ms")
  }

  /** Whether `users/[uid]` is in the local cache (a cache-only query never reaches the server). */
  private suspend fun isCached(uid: String): Boolean =
      session.database.collection(USERS_COLLECTION).get(Source.CACHE).await().documents.any {
        it.id == uid
      }

  private suspend fun createProfileAsAdmin(uid: String, email: String) =
      emulator.createAsAdmin(
          USERS_COLLECTION,
          uid,
          mapOf(
              "uid" to uid,
              "email" to email,
              "displayName" to "Test Student",
              "section" to "IN",
              "year" to "BA3",
              "accountType" to "student",
          ),
      )

  /** This test's own app on the emulators: Firebase lets an app connect to them only once. */
  private object GuardedApp {
    val app: FirebaseApp =
        FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setProjectId(FirebaseEmulator.PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("demo-api-key")
                .build(),
            "session-guard",
        )

    val auth: FirebaseAuth =
        FirebaseAuth.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
        }
  }

  private companion object {
    const val FIRST_EMAIL = "first.student@epfl.ch"
    const val SECOND_EMAIL = "second.student@epfl.ch"
    const val GUARD_TIMEOUT_MS = 15_000L
    const val POLL_MS = 50L
  }
}
