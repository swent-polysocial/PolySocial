// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests for #206, then rewrote them against
// a
// local HTTP server for the plain HTTPS call; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.account

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.Collections
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [FirebaseAccountDeletionRepository] against a local HTTP server (the JDK's `HttpServer` on
 * 127.0.0.1) answering like the callable function, so no request leaves the machine. Robolectric is
 * needed for Android's `org.json`. The request runs on [Dispatchers.Unconfined], inline on the test
 * thread, so it takes no virtual time (see `NominatimGeocodingServiceTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FirebaseAccountDeletionRepositoryTest {
  private val server = LocalFunction()

  @After fun stopServer() = server.stop()

  private val tokenResult = mockk<GetTokenResult> { every { token } returns TOKEN }
  private val user =
      mockk<FirebaseUser> { every { getIdToken(false) } returns Tasks.forResult(tokenResult) }
  private val auth = mockk<FirebaseAuth> { every { currentUser } returns user }
  private val database =
      mockk<FirebaseFirestore> { every { waitForPendingWrites() } returns Tasks.forResult(null) }

  private fun repository(
      baseUrl: String = server.url,
      readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
      firestore: () -> FirebaseFirestore = { database },
  ) =
      FirebaseAccountDeletionRepository(
          authProvider = { auth },
          firestore = firestore,
          baseUrl = { baseUrl },
          ioDispatcher = Dispatchers.Unconfined,
          readTimeoutMillis = readTimeoutMillis,
      )

  private fun tokenFails(error: Exception) = tokenReturns(Tasks.forException(error))

  private fun tokenReturns(task: Task<GetTokenResult>) {
    every { user.getIdToken(false) } returns task
  }

  /** Asserts the result for each answer, so a table reads as the mapping it checks. */
  private suspend fun assertAnswers(cases: List<Pair<Answer, AccountDeletionResult>>) {
    for ((answer, expected) in cases) {
      server.answer = answer
      assertEquals(answer.toString(), expected, repository().deleteAccount())
    }
  }

  // ---------- The request ----------

  @Test
  fun deletion_postsTheCallableRequestWithTheUsersToken() = runTest {
    server.answer = Answer(200, DELETED)

    assertEquals(AccountDeletionResult.Deleted, repository().deleteAccount())

    val request = server.requests.single()
    assertEquals("POST", request.method)
    assertEquals("/$DELETE_ACCOUNT_FUNCTION", request.path)
    assertEquals("Bearer $TOKEN", request.authorization)
    assertEquals("application/json", request.contentType)
    assertEquals("""{"data":{}}""", request.body)
  }

  @Test
  fun productionUrl_isTheFunctionsDefaultUrlInItsRegion() {
    assertEquals(
        "https://europe-west6-polysocial-test.cloudfunctions.net",
        deleteAccountBaseUrl("polysocial-test"),
    )
    for (missing in listOf(null, "", " ")) {
      assertThrows(IllegalStateException::class.java) { deleteAccountBaseUrl(missing) }
    }
  }

  // ---------- Answers ----------

  @Test
  fun anyOtherSuccessAnswer_isNotReportedAsDeleted() = runTest {
    assertAnswers(
        listOf(
                """{"result":{"deleted":false}}""",
                """{"result":{"deleted":"true"}}""",
                """{"result":null}""",
                "{}",
                "<html>not json</html>",
                "",
            )
            .map { Answer(200, it) to AccountDeletionResult.UnexpectedError }
    )
  }

  @Test
  fun functionRefusals_mapToTheirResults() = runTest {
    assertAnswers(
        listOf(
            Answer(401, error("UNAUTHENTICATED")) to AccountDeletionResult.NotSignedIn,
            Answer(400, error("FAILED_PRECONDITION", "recent-login-required")) to
                AccountDeletionResult.RecentLoginRequired,
            Answer(400, error("FAILED_PRECONDITION", "association")) to
                AccountDeletionResult.NotAllowed,
            Answer(400, error("FAILED_PRECONDITION", "something-new")) to
                AccountDeletionResult.UnexpectedError,
            Answer(400, error("FAILED_PRECONDITION")) to AccountDeletionResult.UnexpectedError,
        )
    )
  }

  @Test
  fun transientFunctionFailures_returnNetworkError() = runTest {
    assertAnswers(
        listOf(
            Answer(503, error("UNAVAILABLE")) to AccountDeletionResult.NetworkError,
            Answer(504, error("DEADLINE_EXCEEDED")) to AccountDeletionResult.NetworkError,
        )
    )
  }

  @Test
  fun otherFunctionFailures_returnUnexpectedError() = runTest {
    assertAnswers(
        listOf(
                Answer(500, error("INTERNAL")),
                // The function is not deployed (or not in this region).
                Answer(404, error("NOT_FOUND")),
                Answer(404, ""),
                Answer(500, "<html>Server Error</html>"),
                Answer(500, """{"error":"internal"}"""),
                Answer(403, error("PERMISSION_DENIED")),
            )
            .map { it to AccountDeletionResult.UnexpectedError }
    )
  }

  // ---------- Connection ----------

  @Test
  fun unreachableServer_returnsNetworkError() = runTest {
    val closed = LocalFunction()
    val url = closed.url
    closed.stop()

    assertEquals(AccountDeletionResult.NetworkError, repository(baseUrl = url).deleteAccount())
  }

  @Test
  fun noAnswerBeforeTheReadTimeout_returnsNetworkError() = runTest {
    server.answer = Answer(200, DELETED, delayMillis = 2_000)

    assertEquals(
        AccountDeletionResult.NetworkError,
        repository(readTimeoutMillis = 200).deleteAccount(),
    )
  }

  @Test
  fun malformedBaseUrl_returnsUnexpectedError() = runTest {
    assertEquals(
        AccountDeletionResult.UnexpectedError,
        repository(baseUrl = "not a url").deleteAccount(),
    )
  }

  // ---------- Who is signed in, and their token ----------

  @Test
  fun nobodySignedIn_returnsNotSignedInWithoutARequest() = runTest {
    every { auth.currentUser } returns null

    assertEquals(AccountDeletionResult.NotSignedIn, repository().deleteAccount())
    assertEquals(0, server.requests.size)
  }

  @Test
  fun userAlreadyGoneWhenRefreshingTheToken_returnsDeletedWithoutARequest() = runTest {
    tokenFails(FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "test"))

    assertEquals(AccountDeletionResult.Deleted, repository().deleteAccount())
    assertEquals(0, server.requests.size)
  }

  @Test
  fun disabledOrRevokedUser_isNotReportedAsDeleted() = runTest {
    for (code in
        listOf("ERROR_USER_DISABLED", "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN")) {
      tokenFails(FirebaseAuthInvalidUserException(code, "test"))

      assertEquals(code, AccountDeletionResult.NotSignedIn, repository().deleteAccount())
    }
    assertEquals(0, server.requests.size)
  }

  @Test
  fun tokenFetchFailures_mapWithoutARequest() = runTest {
    tokenFails(FirebaseNetworkException("offline"))
    assertEquals(AccountDeletionResult.NetworkError, repository().deleteAccount())

    tokenFails(IllegalStateException("test"))
    assertEquals(AccountDeletionResult.UnexpectedError, repository().deleteAccount())

    every { tokenResult.token } returns null
    tokenReturns(Tasks.forResult(tokenResult))
    assertEquals(AccountDeletionResult.UnexpectedError, repository().deleteAccount())

    assertEquals(0, server.requests.size)
  }

  @Test
  fun cancelledSdkTaskWhileTheCallerRuns_returnsUnexpectedError() = runTest {
    tokenReturns(Tasks.forCanceled())

    assertEquals(AccountDeletionResult.UnexpectedError, repository().deleteAccount())
  }

  // ---------- Pending writes ----------

  @Test
  fun pendingWritesAreSentBeforeTheRequest() = runTest {
    server.answer = Answer(200, DELETED)
    var requestsWhenWaiting = -1
    every { database.waitForPendingWrites() } answers
        {
          requestsWhenWaiting = server.requests.size
          Tasks.forResult(null)
        }

    assertEquals(AccountDeletionResult.Deleted, repository().deleteAccount())
    assertEquals(0, requestsWhenWaiting)
    assertEquals(1, server.requests.size)
  }

  @Test
  fun pendingWritesNotSent_returnsNetworkErrorWithoutARequest() = runTest {
    for (failure in
        listOf<Task<Void>>(
            Tasks.forException(
                FirebaseFirestoreException("test", FirebaseFirestoreException.Code.UNAVAILABLE)
            ),
            Tasks.forCanceled(),
        )) {
      every { database.waitForPendingWrites() } returns failure

      assertEquals(AccountDeletionResult.NetworkError, repository().deleteAccount())
    }
    assertEquals(0, server.requests.size)
  }

  @Test
  fun pendingWritesNeverSent_returnsNetworkErrorAtTheTimeout() = runTest {
    every { database.waitForPendingWrites() } returns TaskCompletionSource<Void>().task

    assertEquals(AccountDeletionResult.NetworkError, repository().deleteAccount())
    assertEquals(ACCOUNT_DELETION_TIMEOUT_MILLIS, currentTime)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun databaseBlockedByACacheClear_returnsUnexpectedErrorWithoutARequest() = runTest {
    val blocked = repository {
      throw IllegalStateException("Firestore cache cleanup must finish before access")
    }

    assertEquals(AccountDeletionResult.UnexpectedError, blocked.deleteAccount())
    assertEquals(0, server.requests.size)
  }

  // ---------- Cancellation and wiring ----------

  @Test
  fun callerCancellation_propagates() = runTest {
    val pending = TaskCompletionSource<Void>()
    every { database.waitForPendingWrites() } returns pending.task
    var result: AccountDeletionResult? = null
    var cancellationReachedCaller = false
    val deletion = launch {
      try {
        result = repository().deleteAccount()
      } catch (cancelled: CancellationException) {
        cancellationReachedCaller = true
        throw cancelled
      }
    }
    runCurrent()

    deletion.cancel()
    pending.setResult(null)
    runCurrent()

    assertNull(result)
    assertTrue(cancellationReachedCaller)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun hiltBinding_isTheFirebaseRepositoryAndDoesNotTouchFirebaseUntilUsed() {
    // No FirebaseApp is initialized here, so resolving Firebase while constructing would throw.
    val bound =
        AccountDeletionModule.accountDeletionRepository(
            Provider { throw AssertionError("Firestore resolved at construction") }
        )

    assertTrue(bound is FirebaseAccountDeletionRepository)
  }

  private fun runTest(block: suspend TestScope.() -> Unit) =
      kotlinx.coroutines.test.runTest { block() }

  /** What the local function answers; [delayMillis] holds the answer back. */
  private data class Answer(val status: Int, val body: String, val delayMillis: Long = 0)

  private data class Received(
      val method: String,
      val path: String,
      val authorization: String?,
      val contentType: String?,
      val body: String,
  )

  /** A local server standing in for the callable function. */
  private class LocalFunction {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @Volatile var answer = Answer(200, DELETED)
    val requests: MutableList<Received> = Collections.synchronizedList(mutableListOf())

    val url: String
      get() = "http://127.0.0.1:${http.address.port}"

    init {
      http.createContext("/") { exchange ->
        requests +=
            Received(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = exchange.requestBody.bufferedReader().use { it.readText() },
            )
        val current = answer
        if (current.delayMillis > 0) Thread.sleep(current.delayMillis)
        val bytes = current.body.toByteArray()
        // -1 sends no body at all, as a bare 404 from the frontend would.
        exchange.sendResponseHeaders(
            current.status,
            if (bytes.isEmpty()) -1 else bytes.size.toLong(),
        )
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
      }
      http.start()
    }

    fun stop() = http.stop(0)
  }

  private companion object {
    const val TOKEN = "test-id-token"
    const val DELETED = """{"result":{"deleted":true}}"""

    fun error(status: String, details: String? = null): String {
      val detailsField = details?.let { ""","details":"$it"""" }.orEmpty()
      return """{"error":{"message":"test","status":"$status"$detailsField}}"""
    }
  }
}
