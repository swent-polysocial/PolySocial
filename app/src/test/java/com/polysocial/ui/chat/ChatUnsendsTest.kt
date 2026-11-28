// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #436).
package com.polysocial.ui.chat

import com.polysocial.model.chat.UnsendMessageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ChatUnsends], shared by the one-to-one chat (#436) and later the group chat (#440): confirm,
 * then unsend, keeping a failure to try again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatUnsendsTest {

  /** The message IDs [ChatUnsends.confirm] delivered, in order. */
  private val unsentIds = mutableListOf<String>()

  private fun TestScope.unsends() = ChatUnsends(this)

  private fun ChatUnsends.confirmAnswering(result: UnsendMessageResult) = confirm { id ->
    unsentIds += id
    result
  }

  private fun stage(id: String, stage: ChatUnsend.Stage) = ChatUnsend(id, stage)

  @Test
  fun askingConfirms_andUnsentClearsIt() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = unsends()

        unsends.ask("m1")
        assertEquals(stage("m1", ChatUnsend.Stage.CONFIRMING), unsends.unsend.value)
        unsends.confirmAnswering(UnsendMessageResult.Unsent)

        assertEquals(listOf("m1"), unsentIds)
        assertNull(unsends.unsend.value)
      }

  @Test
  fun closingTheConfirmation_unsendsNothing() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = unsends()
        unsends.ask("m1")

        unsends.close()
        unsends.confirmAnswering(UnsendMessageResult.Unsent)

        assertNull(unsends.unsend.value)
        assertEquals(emptyList<String>(), unsentIds)
      }

  @Test
  fun offlineAndOtherFailures_areKept_andTryAgainUnsendsTheSameMessage() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = unsends()
        unsends.ask("m1")

        unsends.confirmAnswering(UnsendMessageResult.NetworkError)
        assertEquals(stage("m1", ChatUnsend.Stage.OFFLINE), unsends.unsend.value)
        unsends.confirmAnswering(UnsendMessageResult.UnexpectedError)
        assertEquals(stage("m1", ChatUnsend.Stage.FAILED), unsends.unsend.value)
        unsends.confirmAnswering(UnsendMessageResult.Unsent)

        assertEquals(listOf("m1", "m1", "m1"), unsentIds)
        assertNull(unsends.unsend.value)
      }

  @Test
  fun anException_isAFailure() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = unsends()
        unsends.ask("m1")

        unsends.confirm { throw IllegalStateException("boom") }

        assertEquals(stage("m1", ChatUnsend.Stage.FAILED), unsends.unsend.value)
      }

  @Test
  fun whileUnsending_askingClosingAndConfirmingAreIgnored() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = unsends()
        val answer = CompletableDeferred<UnsendMessageResult>()
        unsends.ask("m1")
        unsends.confirm { id ->
          unsentIds += id
          answer.await()
        }
        val unsending = stage("m1", ChatUnsend.Stage.UNSENDING)
        assertEquals(unsending, unsends.unsend.value)

        unsends.ask("m2")
        unsends.close()
        unsends.confirmAnswering(UnsendMessageResult.Unsent)
        assertEquals(unsending, unsends.unsend.value)

        answer.complete(UnsendMessageResult.Unsent)
        assertEquals(listOf("m1"), unsentIds)
        assertNull(unsends.unsend.value)
      }

  @Test
  fun aFailureNotice_isReplacedByAskingAnotherMessage() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = unsends()
        unsends.ask("m1")
        unsends.confirmAnswering(UnsendMessageResult.UnexpectedError)

        unsends.ask("m2")

        assertEquals(stage("m2", ChatUnsend.Stage.CONFIRMING), unsends.unsend.value)
      }

  @Test
  fun aCancelledUnsend_isPutBackAsItWas() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = ChatUnsends(backgroundScope)
        unsends.ask("m1")
        unsends.confirm { CompletableDeferred<UnsendMessageResult>().await() }
        assertEquals(stage("m1", ChatUnsend.Stage.UNSENDING), unsends.unsend.value)

        backgroundScope.coroutineContext.cancelChildren()

        assertEquals(stage("m1", ChatUnsend.Stage.CONFIRMING), unsends.unsend.value)
      }

  @Test
  fun confirmingWithNothingAsked_doesNothing() =
      runTest(UnconfinedTestDispatcher()) {
        val unsends = unsends()

        unsends.confirmAnswering(UnsendMessageResult.Unsent)

        assertNull(unsends.unsend.value)
        assertEquals(emptyList<String>(), unsentIds)
      }
}
