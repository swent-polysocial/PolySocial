// Contributors: Claude (Opus 5.5, Anthropic) (several students signed in at once, for the
// registration bursts, #373; adapted from the group tests' rival app, #232).
package com.polysocial.emulator

import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings

/**
 * Firebase apps on the emulators besides [FirebaseEmulator]'s, one per student signed in at the
 * same time: Firebase Auth holds one user per app. Like [FirebaseEmulator]'s, each app is created
 * once per test process, for the `demo-polysocial` project only, with no disk cache. A test signs
 * its students in with [FirebaseEmulator.signInVerifiedUser] and that app's [StudentApp.auth], and
 * signs them out with [signOutAll] when it ends.
 */
object StudentApps {
  /** How many students can be signed in at once besides [FirebaseEmulator]'s. */
  const val COUNT = 5

  /** One student's app: its Firestore and its Auth, both on the emulators. */
  class StudentApp(val firestore: FirebaseFirestore, val auth: FirebaseAuth)

  val all: List<StudentApp> by lazy { (1..COUNT).map { create("registration-student-$it") } }

  fun signOutAll() = all.forEach { it.auth.signOut() }

  private fun create(name: String): StudentApp {
    val app =
        FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setProjectId(FirebaseEmulator.PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("demo-api-key")
                .build(),
            name,
        )
    val firestore =
        FirebaseFirestore.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
          firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
        }
    val auth =
        FirebaseAuth.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
        }
    return StudentApp(firestore, auth)
  }
}
