// Contributors: Claude (Firestore emulator test setup, #117).
package com.polysocial.emulator

import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.ExternalResource

/**
 * Connects Firestore and Auth to the Firebase Local Emulator Suite and clears both before each
 * test, so every test starts from an empty database with no signed-in user.
 *
 * It uses its own [FirebaseApp] for the `demo-polysocial` project, never the app's default one: a
 * `demo-` project exists only on the emulator, so a test can't reach the real backend even if the
 * emulators aren't running (it fails instead). The emulator loads
 * firebase/firestore/firestore.rules (see firebase.json), so every read and write also goes through
 * the Security Rules.
 *
 * The Firestore instance is shared by all the tests of a run. Its in-memory cache drops documents
 * nobody listens to, but a snapshot listener left open would keep receiving the next test's data,
 * so a test removes every listener it adds.
 */
class FirebaseEmulator : ExternalResource() {

  val firestore: FirebaseFirestore
    get() = Connection.firestore

  val auth: FirebaseAuth
    get() = Connection.auth

  override fun before() = runBlocking {
    allowLocalNetwork()
    auth.signOut()
    delete("$FIRESTORE_URL/emulator/v1/projects/$PROJECT_ID/databases/(default)/documents")
    delete("$AUTH_URL/emulator/v1/projects/$PROJECT_ID/accounts")
  }

  override fun after() {
    auth.signOut()
  }

  /**
   * Creates an account for [email], marks its email as verified (as clicking the link in the
   * verification email would) and signs it in. The ID token is refreshed, so the Security Rules see
   * `email_verified == true`.
   */
  suspend fun signInVerifiedUser(email: String, password: String = TEST_PASSWORD): FirebaseUser {
    val uid = auth.createUserWithEmailAndPassword(email, password).await().user!!.uid
    request(
        "POST",
        "$AUTH_URL/identitytoolkit.googleapis.com/v1/projects/$PROJECT_ID/accounts:update",
        body = JSONObject().put("localId", uid).put("emailVerified", true).toString(),
    )
    val user = auth.currentUser!!
    user.reload().await()
    user.getIdToken(true).await()
    return user
  }

  /**
   * Creates the document [id] in [collection] with [data] as an administrator would, bypassing the
   * Security Rules (for example to set `isAssociationVerified`, which only an admin may set).
   * Values keep their type: a [String], [Boolean], [Int] or [Long], [Double], [Timestamp] or
   * [Instant], or a [List] of these (for example an event's `organizerIds` and `startTime`).
   */
  suspend fun createAsAdmin(collection: String, id: String, data: Map<String, Any>) {
    val fields = JSONObject()
    data.forEach { (key, value) -> fields.put(key, firestoreValue(value)) }
    request(
        "POST",
        "$FIRESTORE_URL/v1/projects/$PROJECT_ID/databases/(default)/documents/" +
            "${Uri.encode(collection)}?documentId=${Uri.encode(id)}",
        body = JSONObject().put("fields", fields).toString(),
    )
  }

  /** [value] in the Firestore REST API's typed form, for example `{"booleanValue": true}`. */
  private fun firestoreValue(value: Any): JSONObject =
      when (value) {
        is String -> JSONObject().put("stringValue", value)
        is Boolean -> JSONObject().put("booleanValue", value)
        // The REST API takes 64-bit integers as strings.
        is Int,
        is Long -> JSONObject().put("integerValue", value.toString())
        is Double -> JSONObject().put("doubleValue", value)
        is Timestamp -> firestoreValue(value.toInstant())
        // Instant.toString() is the RFC 3339 form the REST API expects.
        is Instant -> JSONObject().put("timestampValue", value.toString())
        is List<*> -> {
          val values = JSONArray()
          value.forEach { values.put(firestoreValue(requireNotNull(it) { "Null list item" })) }
          JSONObject().put("arrayValue", JSONObject().put("values", values))
        }
        else -> throw IllegalArgumentException("Unsupported field type: ${value::class.simpleName}")
      }

  /**
   * Android 17 (API 37) blocks apps from local network addresses such as 10.0.2.2 until they're
   * granted ACCESS_LOCAL_NETWORK, which the debug manifest declares. Older versions don't have it.
   */
  private fun allowLocalNetwork() {
    if (Build.VERSION.SDK_INT < API_WITH_LOCAL_NETWORK_PERMISSION) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
        instrumentation.targetContext.packageName,
        "android.permission.ACCESS_LOCAL_NETWORK",
    )
  }

  private suspend fun delete(url: String) = request("DELETE", url)

  /**
   * Sends one request to an emulator's REST API. `Bearer owner` is the emulators' admin token: it
   * bypasses the Security Rules and works only on the emulators.
   */
  private suspend fun request(method: String, url: String, body: String? = null) =
      withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
          connection.requestMethod = method
          connection.setRequestProperty("Authorization", "Bearer owner")
          if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
          }
          val code =
              try {
                connection.responseCode
              } catch (e: IOException) {
                throw IllegalStateException(
                    "Can't reach the Firebase emulators at $HOST. Run these tests with the " +
                        "firebase emulators:exec command in the README.",
                    e,
                )
              }
          check(code in 200..299) { "$method $url failed with HTTP $code" }
        } finally {
          connection.disconnect()
        }
      }

  /** One connection per test process: Firebase lets an app connect to the emulators only once. */
  private object Connection {
    private val app: FirebaseApp =
        FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setProjectId(PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("demo-api-key")
                .build(),
            "emulator",
        )

    val firestore: FirebaseFirestore =
        FirebaseFirestore.getInstance(app).apply {
          useEmulator(HOST, FIRESTORE_PORT)
          // No disk cache, so nothing from a previous test or run is read back.
          firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
        }

    val auth: FirebaseAuth = FirebaseAuth.getInstance(app).apply { useEmulator(HOST, AUTH_PORT) }
  }

  companion object {
    /** The emulators' project, the same one as the rules tests (see the README). */
    const val PROJECT_ID = "demo-polysocial"

    /** The host machine, as seen from an Android emulator. */
    private const val HOST = "10.0.2.2"
    private const val FIRESTORE_PORT = 8080
    private const val AUTH_PORT = 9099
    private const val FIRESTORE_URL = "http://$HOST:$FIRESTORE_PORT"
    private const val AUTH_URL = "http://$HOST:$AUTH_PORT"
    private const val TEST_PASSWORD = "fictional-password-1"
    private const val API_WITH_LOCAL_NETWORK_PERMISSION = 37
  }
}
