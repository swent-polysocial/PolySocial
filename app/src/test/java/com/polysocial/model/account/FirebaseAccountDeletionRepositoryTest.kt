// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests for #206; Mohamed Khellaf (reviewed
// and revised).
package com.polysocial.model.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.FirebaseFunctionsException.Code
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import java.util.concurrent.ExecutionException
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class FirebaseAccountDeletionRepositoryTest {
  private val user = mockk<FirebaseUser>()
  private val auth = mockk<FirebaseAuth> { every { currentUser } returns user }
  private val callable = mockk<HttpsCallableReference>()
  private val functions =
      mockk<FirebaseFunctions> {
        every { getHttpsCallable(DELETE_ACCOUNT_FUNCTION) } returns callable
      }
  private val database =
      mockk<FirebaseFirestore> { every { waitForPendingWrites() } returns Tasks.forResult(null) }
  private val repository = FirebaseAccountDeletionRepository({ auth }, { functions }, { database })

  private fun functionReturns(task: Task<HttpsCallableResult>) {
    every { callable.call() } returns task
  }

  private fun functionAnswers(data: Any?) = functionReturns(Tasks.forResult(callableResult(data)))

  private fun functionFails(error: Exception) = functionReturns(Tasks.forException(error))

  // The SDK's constructors are Kotlin-internal, so the tests stub the values the repository reads.
  private fun callableResult(answer: Any?): HttpsCallableResult =
      mockk(relaxed = true) { every { getData() } returns answer }

  /** A function error as the SDK makes it: with a cause only for a failed connection. */
  private fun functionError(
      code: Code,
      details: Any? = null,
      cause: Throwable? = null,
  ): FirebaseFunctionsException =
      mockk(relaxed = true) {
        every { this@mockk.code } returns code
        every { this@mockk.details } returns details
        every { this@mockk.cause } returns cause
      }

  /** Asserts the result for each failure, so a table reads as the mapping it checks. */
  private suspend fun assertFailures(cases: List<Pair<Exception, AccountDeletionResult>>) {
    for ((error, expected) in cases) {
      functionFails(error)
      assertEquals(error.toString(), expected, repository.deleteAccount())
    }
  }

  @Test
  fun deletedAnswer_returnsDeleted() = runTest {
    functionAnswers(mapOf("deleted" to true))

    assertEquals(AccountDeletionResult.Deleted, repository.deleteAccount())
    verify(exactly = 1) { callable.call() }
  }

  @Test
  fun anyOtherAnswer_isNotReportedAsDeleted() = runTest {
    for (answer in listOf(null, emptyMap<String, Any>(), mapOf("deleted" to false), "deleted")) {
      functionAnswers(answer)

      assertEquals(
          answer.toString(),
          AccountDeletionResult.UnexpectedError,
          repository.deleteAccount(),
      )
    }
  }

  @Test
  fun nobodySignedIn_returnsNotSignedInWithoutCallingTheFunction() = runTest {
    every { auth.currentUser } returns null
    var functionsResolved = false
    val repository =
        FirebaseAccountDeletionRepository(
            { auth },
            {
              functionsResolved = true
              functions
            },
            { database },
        )

    assertEquals(AccountDeletionResult.NotSignedIn, repository.deleteAccount())
    assertEquals(false, functionsResolved)
  }

  @Test
  fun functionRefusals_mapToTheirResults() = runTest {
    assertFailures(
        listOf(
            functionError(Code.UNAUTHENTICATED) to AccountDeletionResult.NotSignedIn,
            functionError(Code.FAILED_PRECONDITION, "recent-login-required") to
                AccountDeletionResult.RecentLoginRequired,
            functionError(Code.FAILED_PRECONDITION, "association") to
                AccountDeletionResult.NotAllowed,
            functionError(Code.FAILED_PRECONDITION, "something-new") to
                AccountDeletionResult.UnexpectedError,
            functionError(Code.FAILED_PRECONDITION) to AccountDeletionResult.UnexpectedError,
        )
    )
  }

  @Test
  fun transientFunctionFailures_returnNetworkError() = runTest {
    assertFailures(
        listOf(
            functionError(Code.UNAVAILABLE) to AccountDeletionResult.NetworkError,
            functionError(Code.DEADLINE_EXCEEDED) to AccountDeletionResult.NetworkError,
            // How the SDK reports a request that could not reach the server.
            functionError(Code.INTERNAL, cause = IOException("no route")) to
                AccountDeletionResult.NetworkError,
        )
    )
  }

  @Test
  fun otherFunctionFailures_returnUnexpectedError() = runTest {
    assertFailures(
        listOf(
            functionError(Code.INTERNAL) to AccountDeletionResult.UnexpectedError,
            // The function is not deployed (or not in this region).
            functionError(Code.NOT_FOUND) to AccountDeletionResult.UnexpectedError,
            functionError(Code.PERMISSION_DENIED) to AccountDeletionResult.UnexpectedError,
            functionError(Code.UNKNOWN) to AccountDeletionResult.UnexpectedError,
        )
    )
  }

  @Test
  fun userAlreadyGoneWhenRefreshingTheToken_returnsDeleted() = runTest {
    val missingUser = FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "test")
    assertFailures(
        listOf(
            // The SDK fetches the token in Tasks.whenAll, which wraps the failure.
            ExecutionException("1 out of 2 underlying tasks failed", missingUser) to
                AccountDeletionResult.Deleted,
            missingUser to AccountDeletionResult.Deleted,
        )
    )
  }

  @Test
  fun disabledOrRevokedUser_isNotReportedAsDeleted() = runTest {
    assertFailures(
        listOf("ERROR_USER_DISABLED", "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN").map {
          ExecutionException("test", FirebaseAuthInvalidUserException(it, "test")) to
              AccountDeletionResult.NotSignedIn
        }
    )
  }

  @Test
  fun connectionFailuresBeforeTheCall_returnNetworkError() = runTest {
    assertFailures(
        listOf(
            ExecutionException("test", FirebaseNetworkException("offline")) to
                AccountDeletionResult.NetworkError,
            FirebaseNetworkException("offline") to AccountDeletionResult.NetworkError,
            IOException("offline") to AccountDeletionResult.NetworkError,
        )
    )
  }

  @Test
  fun unknownClientFailure_returnsUnexpectedError() = runTest {
    assertFailures(
        listOf(
            IllegalStateException("test") to AccountDeletionResult.UnexpectedError,
            ExecutionException("test", IllegalStateException("test")) to
                AccountDeletionResult.UnexpectedError,
        )
    )
  }

  @Test
  fun cancelledSdkTaskWhileTheCallerRuns_returnsUnexpectedError() = runTest {
    functionReturns(Tasks.forCanceled())

    assertEquals(AccountDeletionResult.UnexpectedError, repository.deleteAccount())
  }

  @Test
  fun pendingWritesAreSentBeforeTheFunctionIsCalled() = runTest {
    functionAnswers(mapOf("deleted" to true))

    assertEquals(AccountDeletionResult.Deleted, repository.deleteAccount())
    verifyOrder {
      database.waitForPendingWrites()
      callable.call()
    }
  }

  @Test
  fun pendingWritesNotSent_returnsNetworkErrorWithoutCalling() = runTest {
    functionAnswers(mapOf("deleted" to true))
    for (failure in
        listOf<Task<Void>>(
            Tasks.forException(
                FirebaseFirestoreException("test", FirebaseFirestoreException.Code.UNAVAILABLE)
            ),
            Tasks.forCanceled(),
        )) {
      every { database.waitForPendingWrites() } returns failure

      assertEquals(AccountDeletionResult.NetworkError, repository.deleteAccount())
    }
    verify(exactly = 0) { callable.call() }
  }

  @Test
  fun pendingWritesNeverSent_returnsNetworkErrorWithoutCalling() = runTest {
    every { database.waitForPendingWrites() } returns TaskCompletionSource<Void>().task

    assertEquals(AccountDeletionResult.NetworkError, repository.deleteAccount())
    assertEquals(ACCOUNT_DELETION_TIMEOUT_MILLIS, currentTime)
    verify(exactly = 0) { callable.call() }
  }

  @Test
  fun databaseBlockedByACacheClear_returnsNetworkErrorWithoutCalling() = runTest {
    val repository =
        FirebaseAccountDeletionRepository(
            { auth },
            { functions },
            { throw IllegalStateException("Firestore cache cleanup must finish before access") },
        )

    assertEquals(AccountDeletionResult.NetworkError, repository.deleteAccount())
    verify(exactly = 0) { callable.call() }
  }

  @Test
  fun noAnswerInTime_returnsNetworkError() = runTest {
    // A connection lost after the headers leaves the SDK's task pending forever.
    functionReturns(TaskCompletionSource<HttpsCallableResult>().task)

    assertEquals(AccountDeletionResult.NetworkError, repository.deleteAccount())
    assertEquals(ACCOUNT_DELETION_TIMEOUT_MILLIS, currentTime)
  }

  @Test
  fun answerJustBeforeTheTimeout_isUsed() = runTest {
    val pending = TaskCompletionSource<HttpsCallableResult>()
    functionReturns(pending.task)
    val deletion = async { repository.deleteAccount() }

    advanceTimeBy(ACCOUNT_DELETION_TIMEOUT_MILLIS - 1)
    pending.setResult(callableResult(mapOf("deleted" to true)))

    assertEquals(AccountDeletionResult.Deleted, deletion.await())
  }

  @Test
  fun callerCancellation_propagates() = runTest {
    val pending = TaskCompletionSource<HttpsCallableResult>()
    functionReturns(pending.task)
    var result: AccountDeletionResult? = null
    var cancellationReachedCaller = false
    val deletion = launch {
      try {
        result = repository.deleteAccount()
      } catch (cancelled: CancellationException) {
        cancellationReachedCaller = true
        throw cancelled
      }
    }
    runCurrent()

    deletion.cancel()
    pending.setResult(callableResult(mapOf("deleted" to true)))
    runCurrent()

    assertNull(result)
    assertTrue(cancellationReachedCaller)
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
}
