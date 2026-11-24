// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, from Mohamed Khellaf's review of
// #519, #431).
package com.polysocial.ui.chat

import com.polysocial.model.chat.MAX_MESSAGE_LENGTH
import com.polysocial.model.chat.SendMessageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ChatDrafts], shared by the group chat (#238) and the one-to-one chat (#431): a failed draft is
 * retried under its own message ID while it is the same message, and gets a new one once edited.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftsTest {

  /** The IDs [ChatDrafts.send] delivered, in order. */
  private val sentIds = mutableListOf<String>()

  private fun TestScope.drafts() = ChatDrafts(this)

  /** Sends the draft, answering [result]. */
  private fun ChatDrafts.sendAnswering(result: SendMessageResult) = send { _, id ->
    sentIds += id
    result
  }

  @Test
  fun aFailedDraftEditedToAnotherMessage_getsANewId() =
      runTest(UnconfinedTestDispatcher()) {
        val drafts = drafts()
        drafts.edit("Meet at six")
        drafts.sendAnswering(SendMessageResult.UnexpectedError)
        val failed = drafts.draft.value
        assertEquals(ChatDraft.Stage.FAILED, failed.stage)
        assertNotNull(failed.id)

        drafts.edit("Meet at seven")
        assertNull(drafts.draft.value.id)
        drafts.sendAnswering(SendMessageResult.Sent)

        assertEquals(2, sentIds.size)
        assertNotEquals(sentIds[0], sentIds[1])
      }

  @Test
  fun aFailedDraftEditedBackToTheSameTrimmedText_keepsItsId() =
      runTest(UnconfinedTestDispatcher()) {
        val drafts = drafts()
        drafts.edit("Meet at six")
        drafts.sendAnswering(SendMessageResult.NetworkError)
        val id = drafts.draft.value.id

        drafts.edit("  Meet at six ")
        assertEquals(id, drafts.draft.value.id)
        drafts.sendAnswering(SendMessageResult.Sent)

        assertEquals(listOf(id, id), sentIds)
        assertEquals(ChatDraft(), drafts.draft.value)
      }

  @Test
  fun aSentDraft_isCleared_andTheNextMessageGetsANewId() =
      runTest(UnconfinedTestDispatcher()) {
        val drafts = drafts()
        drafts.edit("Hi")
        drafts.sendAnswering(SendMessageResult.Sent)
        assertEquals(ChatDraft(), drafts.draft.value)

        drafts.edit("Hi")
        drafts.sendAnswering(SendMessageResult.Sent)

        assertNotEquals(sentIds[0], sentIds[1])
      }

  @Test
  fun aDraftThatCantBeSent_isntDelivered() =
      runTest(UnconfinedTestDispatcher()) {
        val drafts = drafts()
        drafts.edit("   ")
        drafts.sendAnswering(SendMessageResult.Sent)

        assertEquals(emptyList<String>(), sentIds)
      }

  @Test
  fun whileSending_editsAndASecondSendAreIgnored() =
      runTest(UnconfinedTestDispatcher()) {
        val drafts = drafts()
        val answer = CompletableDeferred<SendMessageResult>()
        drafts.edit("Hi")
        drafts.send { _, id ->
          sentIds += id
          answer.await()
        }
        assertEquals(ChatDraft.Stage.SENDING, drafts.draft.value.stage)

        drafts.edit("Something else")
        drafts.sendAnswering(SendMessageResult.Sent)
        assertEquals("Hi", drafts.draft.value.text)
        assertEquals(1, sentIds.size)

        answer.complete(SendMessageResult.Sent)
        assertEquals(ChatDraft(), drafts.draft.value)
      }

  @Test
  fun anOverlongText_isCutWithoutSplittingAnEmoji() =
      runTest(UnconfinedTestDispatcher()) {
        val drafts = drafts()
        val emoji = "😀"

        drafts.edit("a".repeat(MAX_MESSAGE_LENGTH - 1) + emoji)

        assertEquals("a".repeat(MAX_MESSAGE_LENGTH - 1), drafts.draft.value.text)
      }

  @Test
  fun clear_dropsTheDraft() =
      runTest(UnconfinedTestDispatcher()) {
        val drafts = drafts()
        drafts.edit("Hi")

        drafts.clear()

        assertEquals(ChatDraft(), drafts.draft.value)
      }
}
